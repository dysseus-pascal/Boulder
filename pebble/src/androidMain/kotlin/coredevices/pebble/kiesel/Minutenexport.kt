package coredevices.pebble.kiesel

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * VORUEBERGEHEND, wie der Nachtexport: die Minuten eines gewaehlten Zeitraums als CSV, damit die
 * Velo-Erkennung der Firmware an echten Fahrten und echtem Alltag eingestellt werden kann (F33).
 * Faellt weg, sobald ihre Schwellen stehen.
 *
 * DIE ERSTEN SECHS SPALTEN WIE IM NACHTEXPORT, damit dieselben Werkzeuge die Datei lesen; das
 * Datum steht dahinter, weil ein Zeitraum ueber Mitternacht reichen kann. Was dazu bekannt ist,
 * steht in Kommentarzeilen davor: Ruhepuls und Firmware, mit denen die Uhr rechnete, Trainings,
 * Sitzungen der Uhr und Naechte.
 */
internal object Minutenexport {
    /** Eine Fahrt samt Alltag davor und danach braucht nicht mehr; 7 Tage sind gut 10 000 Zeilen. */
    const val HOECHSTENS_S = 7 * 86_400L

    /** Um so viel weitet die Schnellwahl ein Training: die Minuten davor und danach sind der Vergleich. */
    const val RAND_S = 15 * 60L

    /** Die Dauern der Knoepfe, in Minuten. */
    val DAUERN = listOf(30, 60, 120, 240, 720, 1440)

    // Die Abfrage der Sitzungen nimmt nur, was danach beginnt: eine Fahrt, die vor dem Zeitraum
    // begann, soll trotzdem dastehen. Laenger misst die Uhr keine (KONTEXT_HOECHSTENS_MIN).
    private const val SITZUNG_VORLAUF_S = 6 * 3600L
    private val SITZUNGSARTEN = listOf(OverlayType.Walk, OverlayType.Run, OverlayType.OpenWorkout, OverlayType.Movement).map { it.value }

    private const val ORDNER = "exporte"

    enum class Fehler { ENDE_VOR_BEGINN, ZU_LANG }

    fun pruefe(von: Long, bis: Long): Fehler? = when {
        bis <= von -> Fehler.ENDE_VOR_BEGINN
        bis - von > HOECHSTENS_S -> Fehler.ZU_LANG
        else -> null
    }

    /** Der Zeitraum um ein Training, auf ganze Minuten nach aussen gerundet. */
    fun umTraining(beginn: Long, ende: Long): Pair<Long, Long> =
        Math.floorDiv(beginn - RAND_S, 60L) * 60 to -Math.floorDiv(-(ende + RAND_S), 60L) * 60

    /** Eine Kommentarzeile: was von [von] bis [bis] war, mit Angaben als Schluessel=Wert. */
    data class Markierung(val art: String, val von: Long, val bis: Long, val angaben: List<Pair<String, Any>> = emptyList())

    fun dateiname(von: Long, zone: ZoneId): String =
        "minuten-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.ofEpochSecond(von).atZone(zone)) + ".csv"

    fun csv(
        von: Long,
        bis: Long,
        ruhepuls: Int?,
        firmware: String?,
        minuten: List<HealthDataEntity>,
        markierungen: List<Markierung>,
        zone: ZoneId,
    ): String {
        val uhr = DateTimeFormatter.ofPattern("HH:mm")
        fun ort(t: Long) = Instant.ofEpochSecond(t).atZone(zone)
        return buildString {
            append("# minuten von=$von bis=$bis ruhepuls=${ruhepuls ?: "-"} firmware=${firmware ?: "-"}\n")
            for (m in markierungen.sortedBy { it.von }) {
                append("# ${m.art} von=${m.von} bis=${m.bis} uhrzeit=${uhr.format(ort(m.von))}-${uhr.format(ort(m.bis))}")
                for ((schluessel, wert) in m.angaben) append(" $schluessel=$wert")
                append('\n')
            }
            append("minute,uhrzeit,schritte,vmc,puls,lage,datum\n")
            for (d in minuten.filter { it.timestamp in von until bis }.sortedBy { it.timestamp }) {
                val z = ort(d.timestamp)
                append("${(d.timestamp - von) / 60},${uhr.format(z)},${d.steps},${d.intensity},${d.heartRate},${d.orientation},")
                append(DateTimeFormatter.ISO_LOCAL_DATE.format(z)).append('\n')
            }
        }
    }

    /** Wie viele Minuten im Zeitraum Daten haben; null, wenn das Lesen scheitert. */
    suspend fun zaehle(api: HealthDataApi, von: Long, bis: Long): Int? = withContext(Dispatchers.IO) {
        versuche("Minutenexport", "Minuten nicht zaehlbar") { api.getHealthDataForRange(von, bis).size }
    }

    /**
     * Liest alles fuer [von, bis) und legt die Datei in den Cache. Null, wenn das Lesen scheitert -
     * mit Grund im Log: der Aufruf laeuft im Scope der Seite, ein Wurf dort beendete die App.
     */
    suspend fun erstelle(context: Context, api: HealthDataApi, libPebble: LibPebble, von: Long, bis: Long): File? =
        withContext(Dispatchers.IO) {
            versuche("Minutenexport", "Minuten nicht lesbar") {
                val zone = ZoneId.systemDefault()
                val naechte = api.getNaechte(von - 2 * 86_400L, bis)
                // Der Ruhepuls, mit dem die Uhr zu Beginn rechnete: der der letzten Nacht davor.
                val ruhepuls = naechte.lastOrNull { it.beginnUtc < von && it.ruhepuls > 0 }?.ruhepuls
                val firmware = libPebble.watches.value.filterIsInstance<KnownPebbleDevice>()
                    .maxByOrNull { it.lastConnected }?.runningFwVersion
                val text = csv(von, bis, ruhepuls, firmware, api.getHealthDataForRange(von, bis),
                    markierungen(context, api, naechte, von, bis), zone)
                File(File(context.cacheDir, ORDNER).apply { mkdirs() }, dateiname(von, zone)).apply { writeText(text) }
            }
        }

    private suspend fun markierungen(context: Context, api: HealthDataApi, naechte: List<NachtEntity>, von: Long, bis: Long): List<Markierung> {
        fun ueberlappt(a: Long, b: Long) = a < bis && b > von
        val trainings = TrainingsDaten.uebersicht(context).trainings.mapNotNull { t ->
            val a = t.sitzung.startTime.epochSecond
            val b = t.sitzung.endTime.epochSecond
            if (!ueberlappt(a, b)) null
            else Markierung("training", a, b, listOf("sport" to t.sport.name, "eigen" to if (t.eigen) 1 else 0))
        }
        val sitzungen = api.getOverlayEntriesAfter(von - SITZUNG_VORLAUF_S, SITZUNGSARTEN).mapNotNull { s ->
            val b = s.startTime + s.duration
            if (!ueberlappt(s.startTime, b)) null
            else Markierung("sitzung", s.startTime, b, listOf(
                "typ" to (OverlayType.fromValue(s.type)?.name ?: s.type),
                "manuell" to if (s.manual) 1 else 0,
                "schritte" to s.steps,
            ))
        }
        val schlaf = naechte.mapNotNull { n ->
            val a = n.beginnUtc + n.einschlafen * 60L
            val b = n.beginnUtc + n.aufwachen * 60L
            if (!ueberlappt(a, b)) null else Markierung("nacht", a, b, listOf("ruhepuls" to n.ruhepuls))
        }
        return trainings + sitzungen + schlaf
    }

    fun teile(context: Context, datei: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", datei)
        val senden = Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, datei.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(senden, datei.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
