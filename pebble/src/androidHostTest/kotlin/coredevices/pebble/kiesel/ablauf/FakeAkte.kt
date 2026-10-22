package coredevices.pebble.kiesel.ablauf

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.util.Collections
import kotlin.reflect.KClass

/**
 * Eine Gesundheitsakte im Speicher - an Stelle von Health Connect, das es in
 * Robolectric nicht gibt (eingesetzt ueber Akte.ersatz).
 *
 * SIE VERHAELT SICH WIE DIE ECHTE, soweit Boulder davon abhaengt: jede App
 * schreibt unter ihrem Paket (Herkunft), eine gleiche clientRecordId ersetzt
 * nur bei gleicher oder hoeherer Fassung, Intervalle zaehlen in einem
 * Zeitraum, wenn sie ihn ueberschneiden, Zeitpunkte im halboffenen Fenster.
 * Aenderungen (getChanges) kommen je Satzart in der Reihenfolge, in der sie
 * geschahen, in Seiten wie bei der Akte. Was Boulder nicht braucht
 * (Aggregate), wirft - ein Aufruf dorthin waere ein neuer Weg, den der Test
 * erst kennen muss.
 */
internal class FakeAkte(private val eigenesPaket: String) : HealthConnectClient {

    class Eintrag(val satz: Record, val herkunft: String)

    private val eintraege = mutableListOf<Eintrag>()
    private var naechsteId = 1

    /** Jede Aenderung in der Reihenfolge, mit der Satzart - auch die einer Loeschung, deren Satz weg ist. */
    private val protokoll = mutableListOf<Pair<KClass<out Record>, Change>>()

    /** Wie viele Aenderungen getChanges je Antwort herausgibt. */
    @Volatile
    var aenderungenJeSeite = 1000

    /** Fuer diese Satzarten meldet die naechste Abfrage von Aenderungen, die Marke sei verfallen (wie nach 30 Tagen ohne Abfrage). */
    val verfalleneMarken: MutableSet<KClass<out Record>> = Collections.synchronizedSet(mutableSetOf())

    /** Wie oft nach Aenderungen gefragt wurde. */
    @Volatile
    var aenderungsabfragen = 0
        private set

    /** Was die Akte als erteilt meldet. */
    @Volatile
    var erteilt: Set<String> = emptySet()

    /**
     * Wie im Hintergrund ohne das Recht, dort zu lesen: eine Abfrage, die fremde Eintraege treffen
     * koennte, weist die Akte ganz ab.
     */
    @Volatile
    var nurEigeneLesbar = false

    /**
     * Wie eine Akte, die im Hintergrund ohne das Recht dazu still filtert: eine Abfrage aller
     * Herkuenfte gibt nur die eigenen Eintraege heraus, ohne Ausnahme.
     */
    @Volatile
    var nurEigeneStill = false

    /** Die Satzarten, deren Lesen die Akte abweist - wie ohne das Leserecht dafuer. */
    @Volatile
    var nichtLesbar: Set<KClass<out Record>> = emptySet()

    /** Wirft fuer einen Satz, den die Akte abweisen soll (etwa ein fehlendes Recht); null nimmt ihn. */
    @Volatile
    var weistAb: (Record) -> Exception? = { null }

    /**
     * Laeuft in jedem Schreiben, bevor die Akte die Saetze nimmt: was in der
     * Zwischenzeit anderswo geschieht (die echte Akte braucht dafuer Zeit).
     */
    @Volatile
    var waehrendDesSchreibens: suspend (List<Record>) -> Unit = {}

    /** Wie oft geschrieben wurde - auch abgewiesen. */
    @Volatile
    var schreibversuche = 0
        private set

    @Synchronized
    fun alle(): List<Eintrag> = eintraege.toList()

    inline fun <reified T : Record> saetze(): List<T> = alle().map { it.satz }.filterIsInstance<T>()

    /** Ein Satz, den [paket] schrieb - eine andere App, oder Boulder auf einem anderen Weg (Minutenpuls). */
    @Synchronized
    fun legeAb(satz: Record, paket: String) {
        val e = Eintrag(gestempelt(satz, "fremd-${naechsteId++}", paket), paket)
        eintraege += e
        protokoll += e.satz::class to UpsertionChange(e.satz)
    }

    /** Einen Satz loeschen, wie es ein Nutzer in der Akte oder im Tagesstrahl tut. */
    @Synchronized
    fun loesche(id: String) {
        weg { it.satz.metadata.id == id }
    }

    /** Entfernt und vermerkt jede Loeschung fuer getChanges. */
    private fun weg(wenn: (Eintrag) -> Boolean) {
        val treffer = eintraege.filter(wenn)
        eintraege.removeAll(treffer)
        treffer.forEach { protokoll += it.satz::class to DeletionChange(it.satz.metadata.id) }
    }

    /** Wie oft nach den erteilten Rechten gefragt wurde. */
    @Volatile
    var rechteGefragt = 0
        private set

    /** Wirft beim Fragen nach den Rechten, wenn gesetzt - wie eine Akte, die gerade nicht antwortet. */
    @Volatile
    var rechteWerfen: Exception? = null

    override val permissionController: PermissionController = object : PermissionController {
        override suspend fun getGrantedPermissions(): Set<String> {
            rechteGefragt++
            rechteWerfen?.let { throw it }
            return erteilt
        }
        override suspend fun revokeAllPermissions() { erteilt = emptySet() }
    }

    override val features: HealthConnectFeatures = object : HealthConnectFeatures {
        override fun getFeatureStatus(feature: Int): Int = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        schreibversuche++
        waehrendDesSchreibens(records)
        // Wie die Akte: weist sie einen Satz ab, steht keiner aus dem Aufruf da.
        records.forEach { r -> weistAb(r)?.let { throw it } }
        return InsertRecordsResponse(synchronized(this) { records.map(::nimm) })
    }

    private fun nimm(r: Record): String {
        val kennung = r.metadata.clientRecordId
        val alt = kennung?.let { k ->
            eintraege.firstOrNull { it.herkunft == eigenesPaket && it.satz::class == r::class && it.satz.metadata.clientRecordId == k }
        }
        // Eine niedrigere Fassung derselben Kennung uebergeht die Akte.
        if (alt != null && r.metadata.clientRecordVersion < alt.satz.metadata.clientRecordVersion) return alt.satz.metadata.id
        val id = alt?.satz?.metadata?.id ?: "id-${naechsteId++}"
        if (alt != null) eintraege.remove(alt)
        val neu = Eintrag(gestempelt(r, id, eigenesPaket), eigenesPaket)
        eintraege += neu
        protokoll += neu.satz::class to UpsertionChange(neu.satz)
        return id
    }

    override suspend fun updateRecords(records: List<Record>) {
        synchronized(this) {
            records.forEach { r ->
                val i = eintraege.indexOfFirst { it.satz.metadata.id == r.metadata.id && it.herkunft == eigenesPaket }
                require(i >= 0) { "Kein eigener Satz ${r.metadata.id}" }
                eintraege[i] = Eintrag(gestempelt(r, r.metadata.id, eigenesPaket), eigenesPaket)
                protokoll += eintraege[i].satz::class to UpsertionChange(eintraege[i].satz)
            }
        }
    }

    override suspend fun deleteRecords(recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>) {
        synchronized(this) {
            weg {
                it.herkunft == eigenesPaket && it.satz::class == recordType &&
                    (it.satz.metadata.id in recordIdsList || it.satz.metadata.clientRecordId in clientRecordIdsList)
            }
        }
    }

    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
        synchronized(this) {
            weg { it.herkunft == eigenesPaket && it.satz::class == recordType && trifft(it.satz, timeRangeFilter) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> {
        val e = synchronized(this) { eintraege.firstOrNull { it.satz::class == recordType && it.satz.metadata.id == recordId } }
            ?: throw IllegalArgumentException("Kein Satz $recordId")
        return ReadRecordResponse(e.satz as T)
    }

    /** Jede Leseanfrage, in der Reihenfolge - wer was mit welcher Herkunft las. */
    val lesungen: MutableList<ReadRecordsRequest<*>> = java.util.Collections.synchronizedList(mutableListOf())

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
        lesungen += request
        val gefragt = request.dataOriginFilter.map { it.packageName }.toSet()
        val quellen = if (nurEigeneStill && gefragt.isEmpty()) setOf(eigenesPaket) else gefragt
        if (nurEigeneLesbar && gefragt != setOf(eigenesPaket)) throw SecurityException("Im Hintergrund nur eigene Eintraege")
        if (request.recordType in nichtLesbar) throw SecurityException("Kein Leserecht fuer ${request.recordType.simpleName}")
        val passend = synchronized(this) {
            eintraege.filter { e ->
                e.satz::class == request.recordType && (quellen.isEmpty() || e.herkunft in quellen) && trifft(e.satz, request.timeRangeFilter)
            }.map { it.satz as T }
        }.sortedBy { beginn(it) }.let { if (request.ascendingOrder) it else it.reversed() }
        // Seiten wie die Akte: die Marke ist hier schlicht die Stelle, an der die naechste beginnt.
        val ab = request.pageToken?.toIntOrNull() ?: 0
        val seite = passend.drop(ab).take(request.pageSize)
        val weiter = (ab + seite.size).takeIf { it < passend.size }?.toString()
        return ReadRecordsResponse(seite, weiter)
    }

    override suspend fun aggregate(request: AggregateRequest): AggregationResult = throw UnsupportedOperationException("aggregate")
    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> =
        throw UnsupportedOperationException("aggregateGroupByDuration")
    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> =
        throw UnsupportedOperationException("aggregateGroupByPeriod")
    /** Die Marke ist die Stelle im Protokoll und die Satzarten, fuer die sie gilt. */
    override suspend fun getChangesToken(request: ChangesTokenRequest): String = synchronized(this) {
        "${protokoll.size}|" + request.recordTypes.joinToString(",") { it.java.name }
    }

    override suspend fun getChanges(changesToken: String): ChangesResponse = synchronized(this) { aenderungen(changesToken) }

    private fun aenderungen(changesToken: String): ChangesResponse {
        aenderungsabfragen++
        val (stelle, arten) = changesToken.split('|').let { it[0].toInt() to it[1].split(',').toSet() }
        if (verfalleneMarken.removeIf { it.java.name in arten }) {
            return ChangesResponse(emptyList(), changesToken, hasMore = false, changesTokenExpired = true)
        }
        val aenderungen = mutableListOf<Change>()
        var i = stelle
        while (i < protokoll.size && aenderungen.size < aenderungenJeSeite) {
            val (art, c) = protokoll[i++]
            if (art.java.name in arten) aenderungen += c
        }
        val mehr = (i until protokoll.size).any { protokoll[it].first.java.name in arten }
        return ChangesResponse(aenderungen, "$i|" + arten.joinToString(","), hasMore = mehr, changesTokenExpired = false)
    }

    companion object {
        /**
         * Wann ein Satz beginnt und endet: Intervalle haben startTime/endTime,
         * Zeitpunkte time. Ueber die Getter, denn IntervalRecord ist in der
         * Bibliothek nur intern sichtbar.
         */
        fun beginn(r: Record): Instant = zeit(r, "getStartTime") ?: zeit(r, "getTime")!!

        private fun ende(r: Record): Instant = zeit(r, "getEndTime") ?: beginn(r)

        private fun intervall(r: Record) = zeit(r, "getStartTime") != null

        private fun zeit(r: Record, getter: String): Instant? =
            r.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }?.invoke(r) as Instant?

        /** Ein Intervall zaehlt, wenn es den Zeitraum ueberschneidet; ein Zeitpunkt, wenn er in [von, bis) liegt. */
        private fun trifft(r: Record, f: TimeRangeFilter): Boolean {
            val von = f.startTime ?: Instant.MIN
            val bis = f.endTime ?: Instant.MAX
            return if (intervall(r)) beginn(r).isBefore(bis) && ende(r).isAfter(von)
            else !beginn(r).isBefore(von) && beginn(r).isBefore(bis)
        }

        /**
         * Der Satz mit der Kennung und Herkunft, die die Akte vergibt. Die
         * Satzklassen haben dafuer keinen oeffentlichen Weg: das Feld wird
         * hier wie in der Akte selbst gesetzt.
         */
        private fun gestempelt(r: Record, id: String, paket: String): Record {
            val m = r.metadata
            // Der volle Konstruktor ist in der Bibliothek intern - die Akte selbst darf ihn rufen, der Test ueber Reflexion.
            val neu = Metadata::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType, String::class.java, DataOrigin::class.java, Instant::class.java,
                String::class.java, Long::class.javaPrimitiveType, Device::class.java,
            ).apply { isAccessible = true }
                .newInstance(m.recordingMethod, id, DataOrigin(paket), Instant.now(), m.clientRecordId, m.clientRecordVersion, m.device)
            var k: Class<*>? = r.javaClass
            while (k != null) {
                val feld = k.declaredFields.firstOrNull { it.name == "metadata" }
                if (feld != null) {
                    feld.isAccessible = true
                    feld.set(r, neu)
                    return r
                }
                k = k.superclass
            }
            error("Kein Metadatenfeld in ${r.javaClass}")
        }
    }
}
