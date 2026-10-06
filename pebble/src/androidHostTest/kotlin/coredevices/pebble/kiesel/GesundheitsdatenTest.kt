package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.connection.Pulsreihe
import io.rebble.libpebblecommon.database.dao.DailyMovementAggregate
import io.rebble.libpebblecommon.database.dao.HealthAggregates
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * SEITE UND WIDGET ZEIGEN DIESELBEN ZAHLEN. Das Widget liest schlanker
 * ([Gesundheitsdaten.liesFuerWidget]: ohne Minutenpuls, SpO2, Blutdruck,
 * HRV-Band, Akku), beide laufen aber durch [Gesundheitsdaten.lade]. Der Test
 * fuettert beide aus derselben Quelle und vergleicht alles, was das Widget
 * zeigt - bis zur fertigen Lage. Dazu die [Ladestelle]: geteiltes Lesen,
 * Frische, Veralten und Abbruch.
 */
class GesundheitsdatenTest {
    private val zone = ZoneId.of("Europe/Zurich")

    /** Eine Quelle aus festen Zufallsdaten, die mitschreibt, was gefragt wurde. */
    private class Quelle(
        seed: Int,
        jetzt: Instant,
        private val zone: ZoneId,
        private val apps: Set<Uuid>?,
        /** Genau diese Naechte statt der zufaelligen. */
        naechteStatt: List<NachtEntity>? = null,
    ) : Gesundheitsquelle {
        val gefragt = mutableListOf<String>()
        private val r = Random(seed)
        private val heute = jetzt.atZone(zone).toLocalDate()

        // 45 Naechte, dazu ein Kurzschlaf am Morgen mancher Tage (eine zweite Nacht fuer denselben Tag).
        private val naechteAlle: List<NachtEntity> = naechteStatt ?: (0..45).flatMap { d ->
            val beginn = heute.minusDays(d.toLong() + 1).atTime(21, 0).atZone(zone).toEpochSecond()
            val ein = 30 + r.nextInt(120)
            val auf = ein + 300 + r.nextInt(240)
            buildList {
                if (r.nextInt(8) != 0) add(NachtEntity(beginn, 7200, ein, auf, auf - ein - r.nextInt(40), if (r.nextInt(5) == 0) 0 else 45 + r.nextInt(15), if (r.nextInt(4) == 0) 0 else 30 + r.nextInt(40)))
                if (r.nextInt(6) == 0) add(NachtEntity(beginn + 13 * 3600, 7200, 0, 40, 35, 0, 0))
            }
        }

        private val zeitHeute = jetzt.epochSecond

        // Die Antworten haengen vom erfragten Fenster ab: so faellt ein verschobenes Fenster im Vergleich auf.
        override suspend fun summe(von: Long, bis: Long): HealthAggregates {
            gefragt += "summe"
            return HealthAggregates(8432, 120_000, 1_500_000, (bis - von) / 600, 612_345)
        }

        override suspend fun tageswerte(von: Long, bis: Long): List<DailyMovementAggregate> {
            gefragt += "tageswerte"
            return (1..10).map { heute.minusDays(it.toLong()) }.filter { it.atStartOfDay(zone).toEpochSecond() in von until bis }
                .map { DailyMovementAggregate(it.toString(), 5000L + it.dayOfMonth * 700, null, null, null, null) }
        }

        override suspend fun naechte(von: Long, bis: Long): List<NachtEntity> {
            gefragt += "naechte $von"
            return naechteAlle.filter { it.beginnUtc in von until bis }.sortedBy { it.beginnUtc }
        }

        override suspend fun abschnitte(beginnUtc: Long): List<NachtAbschnittEntity> {
            gefragt += "abschnitte"
            var m = 0
            return (0 until 12).map { val l = 10 + r.nextInt(50); NachtAbschnittEntity(beginnUtc, m, m + l, r.nextInt(5)).also { m += l } }
        }

        override suspend fun puls(von: Long, bis: Long): Pulsreihe {
            gefragt += "puls"
            val zeiten = (von until bis step 120).toList().toLongArray()
            return Pulsreihe(zeiten, IntArray(zeiten.size) { 50 + (zeiten[it] / 120 % 70).toInt() })
        }

        override suspend fun schritte(von: Long, bis: Long): List<Pair<Long, Long>> {
            gefragt += "schritte $von"
            return (von / 1800 * 1800 + 1800 until bis step 1800).map { it to 100 + it / 1800 % 900 }
        }

        override suspend fun spo2(von: Long, bis: Long): List<Pair<Long, Int>> {
            gefragt += "spo2"
            return listOf(von + 60 to 96, bis - 60 to 94)
        }

        override suspend fun letzterZeitstempel(): Long {
            gefragt += "letzte"
            return zeitHeute - 300
        }

        /** Ab wann zuletzt Wasser gefragt wurde - wie [blutdruckVon]. */
        var wasserVon: Instant? = null

        override suspend fun wasser(von: Instant, bis: Instant): List<Wasserroh> {
            gefragt += "wasser"
            wasserVon = von
            fun um(stunden: Long) = Instant.ofEpochSecond(zeitHeute - stunden * 3600 + 17)
            fun tag(vor: Long, h: Int, m: Int) = heute.minusDays(vor).atTime(h, m).atZone(zone).toInstant()
            return listOf(
                Wasserroh(um(1), "w1", true, 250.0), Wasserroh(um(3), "w2", false, 333.3), Wasserroh(um(5), "w3", true, 249.6),
                Wasserroh(um(20), "w4", true, 250.0), Wasserroh(um(23), "w5", false, 500.0),
                // Knapp innerhalb der 24 Stunden: ein um eine Stunde kuerzeres Fenster verloere es.
                Wasserroh(Instant.ofEpochSecond(zeitHeute - 85_000), "w6", true, 250.0),
                // Die Woche der Seite Water: frueher Morgen und spaeter Abend gehoeren je zu ihrem Tag,
                // der Tag vor der Woche zu keinem.
                Wasserroh(tag(2, 10, 0), "w7", true, 250.0), Wasserroh(tag(2, 15, 30), "w8", false, 500.4),
                Wasserroh(tag(4, 0, 5), "w9", true, 250.0), Wasserroh(tag(6, 23, 50), "w10", true, 300.0),
                Wasserroh(tag(7, 12, 0), "w11", true, 999.0),
            ).filter { !it.zeit.isBefore(von) && it.zeit.isBefore(bis) }
        }

        override suspend fun nahrung(von: Instant, bis: Instant): List<Nahrungroh> {
            gefragt += "nahrung"
            fun um(stunden: Long) = Instant.ofEpochSecond(zeitHeute - stunden * 3600 + 29)
            return listOf(
                Nahrungroh(um(1), "n1", true, "koffein-1", 95.0, 2.0, false, "Espresso"),
                Nahrungroh(um(2), "n2", false, null, 40.0, null, false, null),
                Nahrungroh(um(4), "n3", true, "supcycle-1", null, null, false, "Zinc"),
                Nahrungroh(um(6), "n4", false, null, null, 650.0, true, "Lunch"),
                Nahrungroh(um(21), "n5", true, "koffein-2", 80.0, null, false, "Coffee"),
                Nahrungroh(Instant.ofEpochSecond(zeitHeute - 84_300), "n6", true, "koffein-3", 60.0, null, false, "Tea"),
            ).filter { !it.zeit.isBefore(von) && it.zeit.isBefore(bis) }
        }

        /** Ab wann zuletzt Blutdruck gefragt wurde - "gefragt" bleibt beim blossen Namen, den der Widget-Test sucht. */
        var blutdruckVon: Instant? = null

        override suspend fun blutdruck(von: Instant, bis: Instant): List<Blutdruck> {
            gefragt += "blutdruck"
            blutdruckVon = von
            // Jeden Tag eine Messung, juengste zuerst - die Seite ordnet selbst.
            return (0..40).map { Blutdruck(Instant.ofEpochSecond(zeitHeute - it * 86_400L - 3 * 3600), 115 + it % 11, 75 + it % 7) }
                .filter { !it.zeit.isBefore(von) && it.zeit.isBefore(bis) }
        }

        override suspend fun apps(): Set<Uuid>? = apps
        override suspend fun maxpuls(ruhe: Int): Int {
            gefragt += "maxpuls"
            return 185
        }
        override fun wasserZiel() = Drinktervall.Ziel(8, 250)
        override fun supplemente() = SupCycle.Stand(
            heute, listOf("Zinc", "", "D3"), 0b111, 0b001,
            listOf(
                SupCycle.Platz("Zinc", LocalTime.of(8, 0), true, 1, 0, 0, 0),
                SupCycle.Platz("", LocalTime.of(13, 0), true, 1, 0, 0, 0),
                SupCycle.Platz("D3", LocalTime.of(20, 30), true, 1, 0, 0, 0),
            ),
        )
        override val koffeinName = "Caffeine"
    }

    /** Eine Nacht vergleichbar: die Phasen als Liste - data class vergleicht ein IntArray nur als Objekt. */
    private fun vergleichbar(n: Nacht?) = n?.let { listOf(it.beginnUtc, it.einschlafen, it.aufwachen, it.schlafMinuten, it.phasen.toList(), it.abschnitte, it.ruhepuls, it.hrv) }

    /** Alles, was das Widget aus dem Stand nimmt - und was die Seite in denselben Zeilen zeigt. */
    private fun widgetteil(s: GesundheitsStand) = listOf(
        s.schritte, s.aktivMinuten, s.meter, vergleichbar(s.nacht), s.ruhepulsWoche, s.wocheSchlaf,
        s.wasserMl, s.glaeser, s.wasserZiel, s.supplemente, s.koffein, s.mitDrinktervall, s.mitSupCycle,
        s.eintraege, s.glasEintraege, s.gestern, s.gesternGlaeser,
    )

    private object Woerter : Texte {
        override fun text(id: Int, vararg a: Any) = "$id" + a.joinToString(",", "(", ")")
        override fun mehrzahl(id: Int, n: Int, vararg a: Any) = "$id#$n" + a.joinToString(",", "(", ")")
    }

    private fun lage(s: GesundheitsStand, jetzt: LocalDateTime, training: Widgetlage.Training?, wunsch: Widgetlage.Art?) =
        Widgetlage.ermittle(Widgetlage.blick(s, jetzt, zone, training, wunsch, imperial = false) { "Slot $it" }, Woerter)

    @Test
    fun widgetZeigtDieselbenZahlenWieDieSeite() = runBlocking {
        val zeiten = listOf(
            LocalDateTime.of(2026, 10, 3, 7, 30), LocalDateTime.of(2026, 10, 3, 13, 5),
            LocalDateTime.of(2026, 10, 3, 21, 40), LocalDateTime.of(2026, 10, 4, 0, 10),
            LocalDateTime.of(2026, 3, 29, 8, 0), LocalDateTime.of(2026, 10, 25, 2, 30),
        )
        val dt = Drinktervall.UUID
        val sc = SupCycle.UUID
        var verglichen = 0
        for ((i, zeit) in zeiten.withIndex()) for (apps in listOf(null, setOf(dt), setOf(dt, sc), emptySet())) for (seed in 1..8) {
            val jetzt = zeit.atZone(zone).toInstant()
            val voll = Gesundheitsdaten.lade(Quelle(seed * 31 + i, jetzt, zone, apps), voll = true, jetzt, zone)
            val schlank = Quelle(seed * 31 + i, jetzt, zone, apps)
            val widget = Gesundheitsdaten.lade(schlank, voll = false, jetzt, zone)
            assertEquals(widgetteil(voll), widgetteil(widget), "$zeit, Apps $apps, Lauf $seed")
            // Bis zur Lage: dieselbe Buehne, dieselben Kacheln, derselbe Satz - auch mit Training und Wunsch.
            val training = Widgetlage.Training(zeit.minusMinutes(50), "Run", 45, 151.0, 8.4, 1_000, false)
            for (t in listOf(null, training)) for (w in listOf(null, Widgetlage.Art.ERINNERUNG)) {
                for (spaeter in listOf(0L, 95, 400)) {
                    val j = zeit.plusMinutes(spaeter)
                    assertEquals(lage(voll, j, t, w), lage(widget, j, t, w), "$j, Apps $apps, Lauf $seed")
                    verglichen++
                }
            }
            // Was nur die Seiten zeigen, liest das Widget nicht: kein Minutenpuls, kein SpO2, kein Blutdruck.
            for (nie in listOf("puls", "spo2", "blutdruck", "tageswerte", "maxpuls", "letzte")) {
                assertTrue(nie !in schlank.gefragt, "Widget fragte $nie")
            }
            // Die Schritte je halbe Stunde zeigt nur das Tagesband der Seite.
            assertTrue(schlank.gefragt.none { it.startsWith("schritte") }, "Widget fragte die Schritte des Bands")
            // Eine Abfrage der Naechte, ab dem Vergleich (9 Tage) statt 37.
            val vonVergleich = jetzt.atZone(zone).toLocalDate().minusDays(Fenster.VERGLEICHSNAECHTE + 2L).atStartOfDay(zone).toEpochSecond()
            assertEquals(listOf("naechte $vonVergleich"), schlank.gefragt.filter { it.startsWith("naechte") })
        }
        assertTrue(verglichen > 1000)
    }

    @Test
    fun dieSeiteLiestDenPulsEinmalUndBehaeltIhn() = runBlocking {
        val jetzt = LocalDateTime.of(2026, 10, 3, 15, 0).atZone(zone).toInstant()
        val q = Quelle(3, jetzt, zone, null)
        val s = Gesundheitsdaten.lade(q, voll = true, jetzt, zone)
        assertEquals(1, q.gefragt.count { it == "puls" })
        // Die Kurve: genau die letzten 24 Stunden, aelteste zuerst.
        assertTrue(s.puls.isNotEmpty() && s.puls.all { it.first >= jetzt.epochSecond - 86_400 })
        assertEquals(s.puls.sortedBy { it.first }, s.puls)
        assertTrue(s.tagesherz.isNotEmpty())
        assertEquals(Fenster.WOCHE_TAGE, s.wocheSchritte.size)
        assertEquals(8432L, s.wocheSchritte.last().second)
    }

    /**
     * DAS BAND BEGINNT BEIM EINSCHLAFEN, auch weiter als 24 Stunden zurueck:
     * spaetabends liegt das Einschlafen der letzten Nacht vor dem Fenster der
     * Herzseite. Sein Puls kommt aus derselben einen Abfrage, die Schritte je
     * halbe Stunde ab demselben Anfang.
     */
    @Test
    fun dasBandBekommtPulsUndSchritteAbDemEinschlafen() = runBlocking {
        var mehrAls24 = 0
        for (seed in 1..8) {
            val jetzt = LocalDateTime.of(2026, 10, 3, 23, 50).atZone(zone).toInstant()
            val q = Quelle(seed, jetzt, zone, null)
            val s = Gesundheitsdaten.lade(q, voll = true, jetzt, zone)
            val n = s.nacht ?: continue
            val von = n.einschlafen.epochSecond - 15 * 60
            assertEquals(1, q.gefragt.count { it == "puls" })
            // Die Quelle hat alle zwei Minuten einen Puls: der erste des Bands liegt hoechstens zwei Minuten nach seinem Anfang.
            assertTrue(s.bandPuls.first().first in von until von + 120, "Band ab ${s.bandPuls.first().first}, Anfang $von")
            assertTrue(s.bandPuls.all { it.first >= von })
            assertEquals(s.bandPuls.sortedBy { it.first }, s.bandPuls)
            // Die 24 Stunden der Herzseite bleiben, wie sie sind.
            assertTrue(s.puls.all { it.first >= jetzt.epochSecond - 86_400 })
            assertTrue(s.puls.isNotEmpty() && s.bandPuls.isNotEmpty())
            if (von < jetzt.epochSecond - 86_400) mehrAls24++
            assertEquals(listOf("schritte $von"), q.gefragt.filter { it.startsWith("schritte") })
            assertTrue(s.schrittBins.isNotEmpty())
        }
        // Mindestens eine Nacht begann vor dem 24-Stunden-Fenster - sonst haette der Test nichts gezeigt.
        assertTrue(mehrAls24 > 0, "keine Nacht vor dem 24-Stunden-Fenster")
    }

    /**
     * DIE HRV-WOCHE NIMMT JE TAG DIE LAENGSTE NACHT MIT HRV (0.52): hat die
     * laengste Nacht eines Morgens keine (die Uhr rechnete sie ohne HRV neu),
     * zaehlt eine kuerzere desselben Morgens mit HRV. Schlaf, die Nacht von
     * heute und alles andere bleiben bei der laengsten.
     */
    @Test
    fun dieHrvWocheNimmtDieLaengsteNachtMitHrv() = runBlocking {
        val heute = LocalDate.of(2026, 10, 6)
        val jetzt = heute.atTime(13, 0).atZone(zone).toInstant()
        /** Eine Nacht ab [ab] Ortszeit, [schlaf] Minuten bis zum Aufwachen. */
        fun nacht(ab: LocalDateTime, schlaf: Int, hrv: Int) =
            NachtEntity(ab.atZone(zone).toEpochSecond(), 7200, 0, schlaf, schlaf, 52, hrv)
        val naechte = listOf(
            // 3.10.: beide mit HRV - die laengste zaehlt, nicht die hoehere.
            nacht(LocalDateTime.of(2026, 10, 2, 22, 30), 450, 61), nacht(LocalDateTime.of(2026, 10, 3, 6, 50), 60, 80),
            // 4.10.: nur eine Nacht, ohne HRV.
            nacht(LocalDateTime.of(2026, 10, 3, 22, 30), 440, 0),
            // Heute: die lange ohne HRV, danach eine kurze mit.
            nacht(LocalDateTime.of(2026, 10, 5, 22, 0), 480, 0), nacht(LocalDateTime.of(2026, 10, 6, 6, 40), 85, 50),
        )
        val s = Gesundheitsdaten.lade(Quelle(1, jetzt, zone, null, naechte), voll = true, jetzt, zone)
        val hrv = s.hrvTage.associate { it.tag to it.wert }
        assertEquals(50, hrv[heute])
        assertEquals(61, hrv[LocalDate.of(2026, 10, 3)])
        assertNull(hrv[LocalDate.of(2026, 10, 4)])
        // Schlaf und die Nacht von heute: weiter die laengste.
        assertEquals(480, s.wocheSchlaf.toMap()[heute])
        assertEquals(480, s.nacht?.schlafMinuten)
        assertNull(s.nacht?.hrv)
    }

    /**
     * DER RUHEPULS DER ZONEN STEHT IM STAND: aus ihm zieht das Tagesband
     * seine Ruhelinie, wenn heute keine Nacht da ist (Tagesbandrechnung.ruhepuls) -
     * aus derselben Rechnung wie seine Grenzen. Der Median der frueheren
     * Naechte, mit der von heute dazu; ohne gemessenen keiner, die Zonen
     * stehen dann auf dem Ersatz (60).
     */
    @Test
    fun derRuhepulsDerZonenStehtImStandAuchOhneNachtVonHeute() = runBlocking {
        val heute = LocalDate.of(2026, 10, 6)
        val jetzt = heute.atTime(13, 0).atZone(zone).toInstant()
        /** Eine Nacht ab 22:30 vor [tage] Tagen, aufgewacht am Tag danach. */
        fun nacht(tage: Long, ruhepuls: Int) =
            NachtEntity(heute.minusDays(tage).atTime(22, 30).atZone(zone).toEpochSecond(), 7200, 0, 450, 450, ruhepuls, 40)
        // Bis vorgestern Abend getragen, heute Nacht nicht. Die Nacht ohne Ruhepuls (0) zaehlt nicht mit.
        val frueher = listOf(nacht(5, 52), nacht(4, 58), nacht(3, 0), nacht(2, 54))

        val ohne = Gesundheitsdaten.lade(Quelle(1, jetzt, zone, null, frueher), voll = true, jetzt, zone)
        assertNull(ohne.nacht)
        assertEquals(54, ohne.ruhepulsZonen)
        // Die Zonen aus genau diesem Ruhepuls und dem Maximalpuls 185 der Quelle: 54 + 0.4/0.6/0.8 * 131.
        assertEquals(listOf(106, 133, 159), ohne.pulsZonen)
        assertEquals(54, Tagesbandrechnung.ruhepuls(ohne))

        // Mit der Nacht von heute (Ruhepuls 60) rechnen die Zonen mit dem Median aller vier, 56 -
        // die Linie im Band bleibt bei der Nacht, die ihr Text nennt.
        val mit = Gesundheitsdaten.lade(Quelle(1, jetzt, zone, null, frueher + nacht(1, 60)), voll = true, jetzt, zone)
        assertEquals(60, mit.nacht?.ruhepuls)
        assertEquals(56, mit.ruhepulsZonen)
        assertEquals(listOf(108, 133, 159), mit.pulsZonen)
        assertEquals(60, Tagesbandrechnung.ruhepuls(mit))

        // Keine Nacht mit Ruhepuls: kein Ruhepuls - die Zonen stehen auf dem Ersatz 60, keine Linie darauf.
        val keiner = Gesundheitsdaten.lade(Quelle(1, jetzt, zone, null, listOf(nacht(3, 0))), voll = true, jetzt, zone)
        assertNull(keiner.ruhepulsZonen)
        assertEquals(listOf(110, 135, 160), keiner.pulsZonen)
        assertNull(Tagesbandrechnung.ruhepuls(keiner))
    }

    /**
     * DIE BLUTDRUCKBAENDER SEHEN SO WEIT ZURUECK WIE DAS HRV-BAND: der
     * aelteste Tag der Woche braucht noch 30 Tage davor, also 37 Tage aus
     * derselben einen Abfrage. Die Karte selbst (letzte Sitzung) zaehlt
     * weiter 30 Tage. Die Quelle hat 41 Tage, jeden Tag eine Messung.
     */
    @Test
    fun dieBlutdruckwocheSiehtSoWeitZurueckWieIhrBand() = runBlocking {
        for (zeit in listOf(LocalDateTime.of(2026, 10, 3, 13, 5), LocalDateTime.of(2026, 10, 4, 0, 10))) {
            val jetzt = zeit.atZone(zone).toInstant()
            val heute = jetzt.atZone(zone).toLocalDate()
            val q = Quelle(5, jetzt, zone, null)
            val s = Gesundheitsdaten.lade(q, voll = true, jetzt, zone)
            assertEquals(1, q.gefragt.count { it == "blutdruck" }, "$zeit")
            val ersterTag = heute.minusDays(Fenster.WOCHE_TAGE - 1L)
            val von = assertNotNull(q.blutdruckVon)
            assertTrue(!von.isAfter(ersterTag.minusDays(30).atStartOfDay(zone).toInstant()), "$zeit: Blutdruck erst ab $von")
            // Dieselbe Woche wie aus allen 41 Tagen der Quelle: es fehlt keiner, den ein Band braucht.
            val alle = q.blutdruck(Instant.EPOCH, jetzt)
            val woche = assertNotNull(s.blutdruckWoche, "$zeit")
            assertEquals(Gesundheitsrechnung.blutdruckwoche(alle, heute, zone), woche, "$zeit")
            assertTrue(woche.oben.all { it.band != null } && woche.unten.all { it.band != null }, "$zeit")
            // Die Karte und die Herzzeile: weiter genau die letzten 30 Tage.
            val grenze = jetzt.minus(Duration.ofDays(Fenster.BLUTDRUCK_TAGE))
            assertEquals(alle.filter { !it.zeit.isBefore(grenze) }.sortedBy { it.zeit }, s.blutdruck, "$zeit")
        }
    }

    /**
     * DIE WASSERWOCHE DER SEITE WATER: sieben Tage bis heute aus derselben
     * einen Abfrage wie das Wasser von heute, je Tag gesummt wie heute - die
     * Saeule von heute ist dieselbe Zahl wie "0.5 of 2.5 l" darueber. Das
     * Widget zeigt sie nicht und liest darum nicht weiter zurueck.
     */
    @Test
    fun dieWasserwocheZaehltSiebenTageWieHeute() = runBlocking {
        val zeiten = listOf(
            LocalDateTime.of(2026, 10, 3, 13, 5), LocalDateTime.of(2026, 10, 4, 0, 10),
            LocalDateTime.of(2026, 3, 29, 8, 0), LocalDateTime.of(2026, 10, 25, 2, 30),
        )
        for (zeit in zeiten) {
            val jetzt = zeit.atZone(zone).toInstant()
            val heute = jetzt.atZone(zone).toLocalDate()
            val q = Quelle(7, jetzt, zone, null)
            val s = Gesundheitsdaten.lade(q, voll = true, jetzt, zone)
            assertEquals(1, q.gefragt.count { it == "wasser" }, "$zeit")
            assertEquals((6 downTo 0).map { heute.minusDays(it.toLong()) }, s.wasserWoche.map { it.first }, "$zeit")
            assertEquals(s.wasserMl, s.wasserWoche.last().second, "$zeit")
            // Jeder Tag: alles Wasser, das in ihn faellt - aus allen Eintraegen der Quelle, auch denen vor der Woche.
            val alle = q.wasser(Instant.EPOCH, jetzt)
            for ((tag, ml) in s.wasserWoche) {
                assertEquals(alle.filter { it.zeit.atZone(zone).toLocalDate() == tag }.sumOf { it.ml }.toInt(), ml, "$zeit, $tag")
            }
            // Die Tage vor gestern sind gefuellt - sonst haette der Vergleich nur das Fenster von heute gesehen.
            assertTrue(s.wasserWoche.dropLast(2).count { it.second > 0 } >= 3, "$zeit: ${s.wasserWoche}")
            val w = Quelle(7, jetzt, zone, null)
            val widget = Gesundheitsdaten.lade(w, voll = false, jetzt, zone)
            assertTrue(widget.wasserWoche.isEmpty(), "$zeit")
            val grenze = minOf(heute.atStartOfDay(zone).toInstant(), jetzt.minusSeconds(86_400))
            assertTrue(!assertNotNull(w.wasserVon).isBefore(grenze), "$zeit: Widget las Wasser ab ${w.wasserVon}")
        }
    }

    /**
     * Gesundheitsdaten.lies aus Boulder 0.46.0, WOERTLICH BIS AUF DIE ZUGRIFFE:
     * Datenbank, Akte und Einstellungen kommen aus derselben Quelle wie bei
     * [Gesundheitsdaten.lade], "jetzt" ist fest, und das Tagesherz rechnet die
     * alte Fassung ([tagesherzAlt]). Der Minutenpuls kam damals als ganze
     * Zeilen und wurde auf Puls > 0 gefiltert - die Quelle gibt dieselben Minuten.
     */
    private suspend fun liesAlt(q: Gesundheitsquelle, jetzt: Instant, zone: ZoneId): GesundheitsStand {
        val log = "Gesundheit"
        val heute = jetzt.atZone(zone).toLocalDate()
        fun anfang(tag: LocalDate) = tag.atStartOfDay(zone).toEpochSecond()

        val summe = versuche(log, "Bewegung heute") { q.summe(anfang(heute), jetzt.epochSecond) }
        val woche = (Fenster.WOCHE_TAGE - 1 downTo 0).map { heute.minusDays(it.toLong()) }
        val tageswerte = versuche(log, "Bewegung der Woche") { q.tageswerte(anfang(woche.first()), anfang(heute)) }.orEmpty()
            .associate { LocalDate.parse(it.day) to (it.steps ?: 0L) } + (heute to (summe?.steps ?: 0L))

        val vonVergleich = anfang(heute.minusDays(Fenster.VERGLEICHSNAECHTE + 2L))
        val vonTagesherz = anfang(heute.minusDays(Fenster.TAGESHERZ_TAGE))
        val vonHrv = anfang(woche.first().minusDays(Fenster.HRV_NAECHTE + 1L))
        val vonAkku = jetzt.minus(Duration.ofDays(Fenster.AKKU_TAGE)).epochSecond
        val alleNaechte = versuche(log, "Naechte") {
            q.naechte(minOf(vonVergleich, vonTagesherz - 86_400, vonHrv, vonAkku), jetzt.epochSecond)
        }.orEmpty()
        fun naechteAb(von: Long) = alleNaechte.filter { it.beginnUtc >= von }

        val naechte = Gesundheitsrechnung.jeTag(naechteAb(vonVergleich), zone)
        val (letzte, frueher) = Gesundheitsrechnung.naechteTeilen(naechte, heute, zone)
        val nacht = letzte?.let { Gesundheitsrechnung.nacht(it, versuche(log, "Abschnitte der Nacht") { q.abschnitte(it.beginnUtc) }.orEmpty()) }
        val schlafJeTag = naechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.schlafMinuten }

        val vor24 = jetzt.epochSecond - 24 * 3600
        val minutenpuls = versuche(log, "Puls") {
            q.puls(minOf(vonTagesherz, vor24), jetzt.epochSecond).let { p -> List(p.size) { p.zeiten[it] to p.werte[it] } }
        }.orEmpty()
        val puls = minutenpuls.filter { it.first >= vor24 }
        val spo2Von = minOf(vor24, nacht?.spanne?.first ?: vor24)
        val spo2 = versuche(log, "SpO2") { q.spo2(spo2Von, jetzt.epochSecond) }.orEmpty()

        val vorEinemTag = jetzt.minus(Duration.ofHours(24))
        val wasserVon = minOf(Instant.ofEpochSecond(anfang(heute)), vorEinemTag)
        val wasser = versuche(log, "Wasser lesen") { q.wasser(wasserVon, jetzt) }.orEmpty()
        val heuteWasser = wasser.filter { it.zeit.atZone(zone).toLocalDate() == heute }
        val nahrung = versuche(log, "Ernaehrung lesen") { q.nahrung(vorEinemTag, jetzt) }.orEmpty()
        val koffein = nahrung.mapNotNull { r -> r.koffeinMg?.takeIf { it > 0 }?.let { r.zeit to it } }
        val alleEintraege = nahrung.mapNotNull { r ->
            val eigen = r.eigen
            val mg = r.koffeinMg?.takeIf { it > 0 }
            val mahlzeit = r.mahlzeit
            val art = Gesundheitsrechnung.einordnung(eigen, r.kennung, mg, r.kcal, mahlzeit) ?: return@mapNotNull null
            val name = r.name?.ifBlank { null } ?: if (mg != null) q.koffeinName else return@mapNotNull null
            Tageseintrag(r.zeit, art == Eintragsart.PRAEPARAT, name, mg, r.id, eigen, r.kennung)
        }
        val eintraege = alleEintraege.filter { it.zeit.atZone(zone).toLocalDate() == heute }
        val gesternWasser = wasser.filter { it.zeit.isAfter(vorEinemTag) && it.zeit.atZone(zone).toLocalDate() < heute }

        val blutdruck = versuche(log, "Blutdruck lesen") {
            q.blutdruck(jetzt.minus(Duration.ofDays(Fenster.BLUTDRUCK_TAGE)), jetzt).orEmpty().sortedBy { it.zeit }
        }.orEmpty()

        val apps = q.apps()
        val ruhe = Herzgrenzen.ruhepuls(frueher.map { it.ruhepuls } + listOfNotNull(nacht?.ruhepuls)) ?: Trainingsanalyse.RUHEPULS_ERSATZ
        val maxpuls = q.maxpuls(ruhe)
        val zonen = Gesundheitsrechnung.pulsZonen(ruhe, maxpuls)
        val tagesherz = versuche(log, "Tagesherz") {
            val schlafSpannen = naechteAb(vonTagesherz - 86_400).map { e ->
                (e.beginnUtc + e.einschlafen * 60L) to (e.beginnUtc + e.aufwachen * 60L)
            }
            tagesherzAlt(minutenpuls.filter { it.first >= vonTagesherz }, schlafSpannen, zonen, jetzt.atZone(zone).toLocalTime(), zone, heute)
        }.orEmpty()
        val langeNaechte = Gesundheitsrechnung.jeTag(naechteAb(vonHrv), zone)
        val hrvTage = versuche(log, "HRV-Woche") {
            val jeTag = langeNaechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.hrv }
            Gesundheitsrechnung.bandwoche(jeTag, heute)
        }.orEmpty()
        val juengste = if (nacht != null) null else langeNaechte.lastOrNull()?.beginnUtc
            ?: versuche(log, "Juengste Nacht") { q.naechte(0, jetzt.epochSecond) }.orEmpty().maxOfOrNull { it.beginnUtc }
        return GesundheitsStand(
            schritte = summe?.steps ?: 0,
            aktivMinuten = summe?.activeMinutes ?: 0,
            meter = (summe?.distanceCm ?: 0) / 100.0,
            wocheSchritte = Gesundheitsrechnung.reihe(woche, tageswerte, 0L),
            nacht = nacht,
            ruhepulsWoche = frueher.map { it.ruhepuls }.filter { it > 0 },
            wocheSchlaf = Gesundheitsrechnung.reihe(woche, schlafJeTag, 0),
            puls = puls,
            spo2 = spo2,
            wasserMl = heuteWasser.sumOf { it.ml }.toInt(),
            glaeser = heuteWasser.filter { it.eigen }.map { it.zeit }.sorted(),
            wasserZiel = q.wasserZiel(),
            supplemente = q.supplemente(),
            koffein = koffein.sortedBy { it.first },
            mitDrinktervall = UhrApps.hat(apps, Drinktervall.UUID),
            mitSupCycle = UhrApps.hat(apps, SupCycle.UUID),
            tagesherz = tagesherz,
            eintraege = eintraege.sortedByDescending { it.zeit },
            blutdruck = blutdruck,
            glasEintraege = heuteWasser.map { Glaseintrag(it.zeit, it.id, it.eigen, it.ml.roundToInt()) },
            letzteDaten = versuche(log, "Letzte Daten") { q.letzterZeitstempel() }?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
            pulsZonen = zonen,
            hrvTage = hrvTage,
            juengsteNacht = juengste,
            gestern = alleEintraege.filter { it.eigen && !it.praeparat && it.zeit.atZone(zone).toLocalDate() < heute }.sortedBy { it.zeit },
            gesternGlaeser = gesternWasser.map { Glaseintrag(it.zeit, it.id, it.eigen, it.ml.roundToInt()) }.filter { it.eigen }.sortedBy { it.zeit },
            akkuNaechte = naechteAb(vonAkku),
        )
    }

    @Test
    fun dieSeiteZeigtDasselbeWieVorher() = runBlocking {
        // Dieselben Uhrzeiten wie oben, auch ueber beide Zeitumstellungen und Mitternacht.
        val zeiten = listOf(
            LocalDateTime.of(2026, 10, 3, 7, 30), LocalDateTime.of(2026, 10, 3, 13, 5),
            LocalDateTime.of(2026, 10, 3, 21, 40), LocalDateTime.of(2026, 10, 4, 0, 10),
            LocalDateTime.of(2026, 3, 29, 8, 0), LocalDateTime.of(2026, 10, 25, 2, 30),
        )
        var verglichen = 0
        val gefuellt = IntArray(7)
        for ((i, zeit) in zeiten.withIndex()) for (apps in listOf(null, setOf(Drinktervall.UUID, SupCycle.UUID))) for (seed in 1..8) {
            val jetzt = zeit.atZone(zone).toInstant()
            val alt = liesAlt(Quelle(seed * 31 + i, jetzt, zone, apps), jetzt, zone)
            val neu = Gesundheitsdaten.lade(Quelle(seed * 31 + i, jetzt, zone, apps), voll = true, jetzt, zone)
            // Die Nacht eigens: ihre Phasen sind ein IntArray, das data class nur als Objekt vergleicht.
            assertEquals(vergleichbar(alt.nacht), vergleichbar(neu.nacht), "$zeit, Apps $apps, Lauf $seed")
            // Was 0.51 fuer das Tagesband dazulas, kannte die alte Fassung nicht - eigens geprueft in dasBand...
            // Ebenso die Blutdruckwoche der Herzseite (0.51) - eigens geprueft in dieBlutdruckwoche...
            // und die Wasserwoche der Seite Water (0.51) - eigens geprueft in dieWasserwoche...
            // Der Ruhepuls der Zonen (0.52) - eigens geprueft in derRuhepulsDerZonen...
            assertEquals(
                alt.copy(nacht = null),
                neu.copy(
                    nacht = null, bandPuls = emptyList(), schrittBins = emptyList(), blutdruckWoche = null, wasserWoche = emptyList(),
                    ruhepulsZonen = null,
                ),
                "$zeit, Apps $apps, Lauf $seed",
            )
            // Der Vergleich ist nicht leer: Puls, Tagesherz, Nacht, Akkunaechte, Eintraege von heute und gestern.
            listOf(neu.puls, neu.tagesherz, listOfNotNull(neu.nacht), neu.akkuNaechte, neu.eintraege, neu.gestern, neu.hrvTage)
                .forEachIndexed { k, teil -> if (teil.isNotEmpty()) gefuellt[k]++ }
            verglichen++
        }
        assertEquals(96, verglichen)
        assertTrue(gefuellt.all { it > 0 }, "leer verglichen: ${gefuellt.toList()}")
    }

    // --- Ladestelle ---

    private class Uhr(var ms: Long = 1_000)

    private fun stelle(uhr: Uhr, gilt: (String) -> Boolean = { true }) =
        Ladestelle(uhr = { uhr.ms }, bereich = CoroutineScope(SupervisorJob() + Dispatchers.Default), gilt = gilt)

    @Test
    fun zweiAufrufeTeilenEinLesen() = runBlocking {
        val s = stelle(Uhr())
        val gelesen = AtomicInteger()
        val tor = CompletableDeferred<Unit>()
        val a = async(Dispatchers.Default) { s.lies(0) { gelesen.incrementAndGet(); tor.await(); "A" } }
        while (gelesen.get() == 0) yield()
        // UNDISPATCHED: b und c stehen sicher in der Warteschlange, bevor das Tor aufgeht - sonst
        // begaenne b ab und zu erst nach dem Lesen von a und laese zu Recht neu (der Test flackerte).
        val b = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { s.lies(0) { gelesen.incrementAndGet(); "B" } }
        // Das Widget wartet mit, statt selbst zu lesen.
        val c = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { s.mitwarten() }
        tor.complete(Unit)
        assertEquals("A", a.await())
        assertEquals("A", b.await())
        assertEquals("A", c.await())
        assertEquals(1, gelesen.get())
    }

    @Test
    fun frischOhneLesenBisZumAlter() = runBlocking {
        val uhr = Uhr()
        val s = stelle(uhr)
        val gelesen = AtomicInteger()
        suspend fun lies(alter: Long) = s.lies(alter) { "L${gelesen.incrementAndGet()}" }
        assertEquals("L1", lies(30_000))
        uhr.ms += 29_999
        assertEquals("L1", lies(30_000))
        // Ohne erlaubtes Alter liest jeder Anlass neu (Schub, Eintrag, Rueckkehr).
        assertEquals("L2", lies(0))
        uhr.ms += 30_000
        assertEquals("L3", lies(30_000))
        assertNull(s.mitwarten())
    }

    @Test
    fun einSchubMachtAltUndTeiltNichtsVonVorher() = runBlocking {
        val s = stelle(Uhr())
        val gelesen = AtomicInteger()
        val tor = CompletableDeferred<Unit>()
        assertEquals("1", s.lies(0) { "${gelesen.incrementAndGet()}" })
        s.veraltet()
        // Nach dem Veralten gilt der eben gelesene Stand nicht mehr als frisch.
        assertNull(s.frisch(60_000))
        assertEquals("2", s.lies(60_000) { "${gelesen.incrementAndGet()}" })
        // Ein Lesen von vor dem Schub wird nicht geteilt: der Schub kann darin fehlen.
        val alt = async(Dispatchers.Default) { s.lies(0) { gelesen.incrementAndGet(); tor.await(); "alt" } }
        while (gelesen.get() < 3) yield()
        s.veraltet()
        assertNull(s.mitwarten())
        assertEquals("neu", s.lies(0) { gelesen.incrementAndGet(); "neu" })
        tor.complete(Unit)
        assertEquals("alt", alt.await())
        // Das spaete alte Lesen ueberschreibt das neuere nicht.
        assertEquals("neu", s.letzter)
        assertEquals(4, gelesen.get())
    }

    @Test
    fun verlassenAlleDasLesenBrichtEsAb() = runBlocking {
        val s = stelle(Uhr())
        val abgebrochen = CompletableDeferred<Boolean>()
        val begonnen = CompletableDeferred<Unit>()
        val seite = async(Dispatchers.Default) {
            s.lies(0) {
                begonnen.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    abgebrochen.complete(true)
                    throw e
                }
            }
        }
        begonnen.await()
        seite.cancel()
        assertTrue(withTimeout(5_000) { abgebrochen.await() })
        assertNull(s.letzter)
    }

    @Test
    fun einGemerkterStandVonGesternGiltNicht() = runBlocking {
        var tag = "Mo"
        val s = stelle(Uhr()) { it == tag }
        assertEquals("Mo", s.lies(0) { "Mo" })
        assertSame("Mo", s.frisch(60_000))
        tag = "Di"
        assertNull(s.frisch(60_000))
        assertEquals("Di", s.lies(60_000) { "Di" })
    }
}
