package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.connection.Pulsreihe
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Das Tagesherz aus Boulder 0.46.0, unveraendert - auch fuer den Vergleich
 * des ganzen Stands mit dem alten Lesen (GesundheitsdatenTest).
 */
internal fun tagesherzAlt(
    puls: List<Pair<Long, Int>>, schlaf: List<Pair<Long, Long>>, grenzen: List<Int>,
    bisUhrzeit: LocalTime, zone: ZoneId, heute: LocalDate? = null,
): List<Gesundheitsrechnung.Tagesherz> {
    val sortiert = puls.filter { it.second > 0 }.sortedBy { it.first }
    val jeTag = linkedMapOf<LocalDate, Triple<Long, Int, Double>>()
    val mitNacht = schlaf.map { Instant.ofEpochSecond(it.second).atZone(zone).toLocalDate() }.toSet()
    val nachtsGemessen = mutableSetOf<LocalDate>()
    sortiert.forEachIndexed { i, (sek, bpm) ->
        if (schlaf.any { (von, bis) -> sek in von until bis }) return@forEachIndexed
        val zeit = Instant.ofEpochSecond(sek).atZone(zone)
        if (zeit.toLocalTime().isAfter(bisUhrzeit)) return@forEachIndexed
        val tag = zeit.toLocalDate()
        val vielleichtSchlaf = zeit.hour < 6L && tag !in mitNacht
        if (vielleichtSchlaf) nachtsGemessen += tag
        val minuten = if (i + 1 < sortiert.size) ((sortiert[i + 1].first - sek) / 60.0).coerceIn(1.0, 10.0) else 1.0
        val gewicht = when (Gesundheitsrechnung.pulsZone(bpm, grenzen)) {
            0 -> 0.0
            1 -> 1.0
            else -> 2.0
        }
        val alt = jeTag[tag] ?: Triple(0L, 0, 0.0)
        jeTag[tag] = if (vielleichtSchlaf) alt.copy(third = alt.third + gewicht * minuten)
        else Triple(alt.first + bpm, alt.second + 1, alt.third + gewicht * minuten)
    }
    val wartet = heute != null && heute in nachtsGemessen && bisUhrzeit.hour < Gesundheitsrechnung.NACHT_SPAETESTENS_H
    return jeTag.map { (tag, w) ->
        val wach = if (w.second >= Gesundheitsrechnung.TAGESHERZ_MINDESTENS && !(wartet && tag == heute)) (w.first.toDouble() / w.second).roundToInt() else null
        Gesundheitsrechnung.Tagesherz(tag, wach, w.third.roundToInt(), w.second)
    }.sortedBy { it.tag }
}

/**
 * DIE SCHNELLEN RECHNUNGEN RECHNEN DASSELBE WIE DIE ALTEN. Tagesherz (Zeiger
 * statt ZonedDateTime je Minute und schlaf.any) und Trendlinie (Fenster aus
 * zwei Zeigern statt filter je Schritt) gegen die Fassung von Boulder 0.46.0,
 * hier woertlich als Vorlage - ueber Zufallsdaten, Zeitumstellungen,
 * Halbstunden-Zonen und ueberlappende Naechte.
 */
class GesundheitsrechnungGleichTest {

    // --- Die Vorlagen aus 0.46.0, unveraendert ---

    private fun trendAlt(punkte: List<Pair<Long, Int>>, fenster: Long, schritt: Long = 300): List<List<Pair<Long, Double>>> {
        if (punkte.isEmpty()) return emptyList()
        val sortiert = punkte.sortedBy { it.first }
        val aus = mutableListOf<MutableList<Pair<Long, Double>>>()
        var offen = false
        var t = sortiert.first().first
        while (t <= sortiert.last().first) {
            val nah = sortiert.filter { abs(it.first - t) <= fenster }
            if (nah.none { it.first <= t } || nah.none { it.first >= t }) offen = false
            else {
                val median = Statistik.median(nah.map { it.second })
                if (!offen) { aus += mutableListOf<Pair<Long, Double>>(); offen = true }
                aus.last() += t to median
            }
            t += schritt
        }
        return aus
    }

    // --- Zufallsdaten ---

    private val zonen = listOf(
        ZoneOffset.UTC, ZoneId.of("Europe/Zurich"), ZoneId.of("America/St_Johns"),
        ZoneId.of("Australia/Lord_Howe"), ZoneId.of("Asia/Kathmandu"), ZoneId.of("America/Santiago"),
    )

    /** Minuten mit Puls ueber [tage] Tage bis [ende], mit Luecken, Doppeln und Nullen wie aus der Uhr. */
    private fun puls(r: Random, ende: Long, tage: Int): List<Pair<Long, Int>> {
        val aus = mutableListOf<Pair<Long, Int>>()
        var t = ende - tage * 86_400L
        while (t < ende) {
            val bpm = when (r.nextInt(20)) {
                0 -> 0
                1 -> 150 + r.nextInt(40)
                else -> 45 + r.nextInt(70)
            }
            aus += t to bpm
            // Meist jede Minute, manchmal Luecken bis 3 h, selten dieselbe Sekunde doppelt.
            t += when (r.nextInt(50)) {
                0 -> r.nextLong(600, 10_800)
                1 -> 0
                else -> 60L * (1 + r.nextInt(5))
            }
        }
        // Nicht immer geordnet: die Rechnung muss selbst ordnen.
        return if (r.nextBoolean()) aus.shuffled(r) else aus
    }

    /** Naechte von 22 bis 7 Uhr, manche ueberlappend, manche leer, eine Kurzschlafspanne am Tag. */
    private fun schlaf(r: Random, ende: Long, tage: Int): List<Pair<Long, Long>> = (0..tage).flatMap { d ->
        val beginn = ende - d * 86_400L - r.nextLong(3_600, 12 * 3_600)
        val lang = r.nextLong(0, 10 * 3_600)
        buildList {
            if (r.nextInt(6) != 0) add(beginn to beginn + lang)
            if (r.nextInt(5) == 0) add(beginn + lang / 2 to beginn + lang + r.nextLong(0, 7_200))
            if (r.nextInt(8) == 0) add(beginn + 100 to beginn + 100)
        }
    }.shuffled(r)

    @Test
    fun tagesherzWieVorher() {
        val r = Random(46)
        repeat(300) { lauf ->
            val zone = zonen[lauf % zonen.size]
            // Um die Zeitumstellungen herum und irgendwo im Jahr.
            val ende = listOf(
                LocalDateTime.of(2026, 3, 29, 12, 0), LocalDateTime.of(2026, 10, 25, 12, 0),
                LocalDateTime.of(2026, 4, 5, 9, 0), LocalDateTime.of(2026, 9, 6, 23, 0),
                LocalDateTime.of(2026, 7, 1, 0, 30).plusMinutes(r.nextLong(0, 365L * 1440)),
            )[lauf % 5].atZone(zone).toEpochSecond()
            val tage = 1 + r.nextInt(15)
            val p = puls(r, ende, tage)
            val s = schlaf(r, ende, tage)
            val grenzen = Gesundheitsrechnung.pulsZonen(45 + r.nextInt(25), 170 + r.nextInt(30))
            val bis = LocalTime.ofNanoOfDay(r.nextLong(0, 86_400L * 1_000_000_000))
            val heute = if (r.nextBoolean()) Instant.ofEpochSecond(ende).atZone(zone).toLocalDate() else null
            assertEquals(tagesherzAlt(p, s, grenzen, bis, zone, heute), Gesundheitsrechnung.tagesherz(p, s, grenzen, bis, zone, heute), "Lauf $lauf in $zone")
        }
    }

    @Test
    fun tagesherzAusDerReiheWieAusPaaren() {
        // Die Datenbank liefert geordnet und nur mit Puls; gerechnet wird ab der Sekunde [ab].
        val r = Random(7)
        repeat(100) { lauf ->
            val zone = zonen[lauf % zonen.size]
            val ende = LocalDateTime.of(2026, 10, 3, 14, 0).minusDays(r.nextLong(0, 200)).atZone(zone).toEpochSecond()
            val p = puls(r, ende, 15).filter { it.second > 0 }.sortedBy { it.first }.distinctBy { it.first }
            val ab = ende - 14 * 86_400L + r.nextLong(-3_600, 3_600)
            val reihe = Pulsreihe(LongArray(p.size) { p[it].first }, IntArray(p.size) { p[it].second })
            val s = schlaf(r, ende, 15)
            val grenzen = Gesundheitsrechnung.pulsZonen(55, 185)
            val bis = LocalTime.of(r.nextInt(24), r.nextInt(60))
            val heute = Instant.ofEpochSecond(ende).atZone(zone).toLocalDate()
            assertEquals(
                tagesherzAlt(p.filter { it.first >= ab }, s, grenzen, bis, zone, heute),
                Gesundheitsrechnung.tagesherz(reihe, ab, s, grenzen, bis, zone, heute),
                "Lauf $lauf",
            )
        }
    }

    @Test
    fun ungeordneteReiheWirdWieSortedByGeordnet() {
        val zone = ZoneId.of("Europe/Zurich")
        val t0 = LocalDateTime.of(2026, 10, 2, 8, 0).atZone(zone).toEpochSecond()
        val p = (0 until 200).map { t0 + it * 60L to 60 + it % 50 }.shuffled(Random(3)) + (t0 to 0)
        val reihe = Pulsreihe(LongArray(p.size) { p[it].first }, IntArray(p.size) { p[it].second })
        val grenzen = Gesundheitsrechnung.pulsZonen(55, 185)
        assertEquals(
            tagesherzAlt(p, emptyList(), grenzen, LocalTime.of(20, 0), zone),
            Gesundheitsrechnung.tagesherz(reihe, 0, emptyList(), grenzen, LocalTime.of(20, 0), zone),
        )
    }

    @Test
    fun trendWieVorher() {
        val r = Random(11)
        repeat(300) { lauf ->
            val t0 = 1_790_000_000L + r.nextLong(0, 1_000_000)
            val n = r.nextInt(0, 700)
            var t = t0
            val punkte = (0 until n).map {
                t += when (r.nextInt(30)) {
                    0 -> r.nextLong(900, 7_200)
                    1 -> 0
                    else -> r.nextLong(1, 240)
                }
                t to 40 + r.nextInt(80)
            }.let { if (r.nextBoolean()) it.shuffled(r) else it }
            val fenster = listOf(0L, 299L, 300L, 900L, 1800L)[lauf % 5]
            val schritt = listOf(300L, 60L, 301L)[lauf % 3]
            assertEquals(trendAlt(punkte, fenster, schritt), Gesundheitsrechnung.trend(punkte, fenster, schritt), "Lauf $lauf")
        }
    }

    @Test
    fun spanneUndSpitzeAusDenBinsWieVorherAusDenPunkten() {
        // Die Vorlagen aus 0.46.0: jede bildete ihre Bins selbst.
        fun spanneAlt(punkte: List<Pair<Long, Int>>): Pair<Int, Int>? {
            val mediane = Gesundheitsrechnung.fuenfMinuten(punkte).map { it.median }
            return if (mediane.isEmpty()) null else mediane.min() to mediane.max()
        }
        fun spitzeAlt(punkte: List<Pair<Long, Int>>): Pair<Long, Int>? {
            val bins = Gesundheitsrechnung.fuenfMinuten(punkte)
            val hoechster = bins.maxOfOrNull { it.median } ?: return null
            return bins.last { it.median == hoechster }.let { it.sekunde to it.median }
        }
        val r = Random(5)
        repeat(50) {
            val punkte = (0 until r.nextInt(0, 1440)).map { 1_790_000_000L + it * 60L + r.nextLong(0, 59) to 40 + r.nextInt(120) }
            val bins = Gesundheitsrechnung.fuenfMinuten(punkte)
            assertEquals(spanneAlt(punkte), Gesundheitsrechnung.spanneDerBins(bins))
            assertEquals(spitzeAlt(punkte), Gesundheitsrechnung.spitzeDerBins(bins))
        }
    }
}
