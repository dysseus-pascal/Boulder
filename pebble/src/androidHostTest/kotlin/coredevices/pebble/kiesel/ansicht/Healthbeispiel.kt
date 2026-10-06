package coredevices.pebble.kiesel.ansicht

import androidx.health.connect.client.records.ExerciseSessionRecord
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Glaseintrag
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.SupCycle
import coredevices.pebble.kiesel.Tageseintrag
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsUebersicht
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Ein Tag zum Zeichnen, nach den Beispielwerten des Entwurfs "Erlebnis-Health":
 * heute 13:40, die Nacht ab 21:46 mit den Phasen des Entwurfs (7:59 h,
 * Ruhepuls 55, HRV 50), Espresso 6:44, drei Praeparate um 8:00 und
 * Magnesium offen um 20:00 (Zink als Kur in Woche 3 von 8), zwei Glaeser und
 * die Wasserwoche des Entwurfs, ein Spaziergang um 10:30, gestern
 * eine Fahrt, vor acht Tagen ein Lauf (immer in einer frueheren Woche).
 * NUR DATEN - gezeichnet wird mit dem Code der App.
 */
internal object Healthbeispiel {
    val zone: ZoneId = ZoneId.systemDefault()
    val heute: LocalDate = LocalDate.now(zone)
    fun um(h: Int, m: Int, tag: LocalDate = heute): Instant = tag.atTime(h, m).atZone(zone).toInstant()
    val jetzt: Instant = um(13, 40)

    /** Die Phasen der echten Nacht 5./6.10. aus dem Entwurf: Beginn in Minuten ab dem Einschlafen, Dauer, Phase. */
    private val lauf = listOf(
        Triple(0, 52, 1), Triple(52, 19, 2), Triple(71, 9, 3), Triple(80, 6, 1), Triple(86, 13, 3), Triple(99, 8, 1),
        Triple(107, 11, 3), Triple(118, 10, 1), Triple(128, 6, 2), Triple(134, 12, 1), Triple(146, 5, 3), Triple(151, 14, 2),
        Triple(165, 35, 1), Triple(200, 14, 2), Triple(214, 24, 1), Triple(238, 9, 2), Triple(247, 18, 3), Triple(265, 19, 1),
        Triple(284, 8, 3), Triple(292, 6, 1), Triple(298, 9, 3), Triple(307, 8, 1), Triple(315, 5, 0), Triple(320, 6, 1),
        Triple(326, 27, 2), Triple(353, 10, 0), Triple(363, 131, 1),
    )

    /** Die Nacht des Entwurfs ab 21:46 am [abend], 494 Minuten bis 6:00, 7:59 h Schlaf, Ruhepuls 55, HRV 50. */
    fun nachtAb(abend: LocalDate): Nacht {
        val einschlafen = um(21, 46, abend)
        return Gesundheitsrechnung.nacht(
            NachtEntity(einschlafen.epochSecond, 7200, 0, 494, 479, 55, 50),
            lauf.map { (von, dauer, phase) -> NachtAbschnittEntity(einschlafen.epochSecond, von, von + dauer, phase) },
        )
    }

    val nacht = nachtAb(heute.minusDays(1))

    /** Der Puls je halbe Stunde ab 21:40 bis jetzt, wie im Entwurf; dazwischen jede Minute gerade verbunden. */
    private val pulsJeHalbeStunde = listOf(
        62, 58, 55, 54, 53, 52, 53, 54, 52, 53, 55, 54, 56, 55, 57, 56, 58, 66, 72, 78, 74, 76, 72, 75, 73, 78, 104, 98, 80, 76, 74, 72, 68,
    )

    /** Jede Minute ab 21:40 gestern bis eine Minute vor jetzt, zwischen den Werten je halbe Stunde gerade verbunden. */
    private fun pulsAus(jeHalbeStunde: List<Int>): List<Pair<Long, Int>> {
        val start = um(21, 40, heute.minusDays(1)).epochSecond
        return (0 until (jeHalbeStunde.size - 1) * 30).map { minute ->
            val i = minute / 30
            val a = jeHalbeStunde[i]
            val b = jeHalbeStunde[i + 1]
            start + minute * 60L to (a + (b - a) * (minute % 30) / 30.0).roundToInt()
        }.filter { it.first < jetzt.epochSecond - 60 }
    }

    /** Ein ruhiger Tag: der Spaziergang um 10:30 kommt bis 104, unter die Grenze zu moderat (107). */
    val bandPuls: List<Pair<Long, Int>> = pulsAus(pulsJeHalbeStunde)

    /**
     * Ein bewegter Tag: derselbe, aber um 10:30 ein Lauf statt des Spaziergangs,
     * bis gut 150 - ueber die Grenze zu kraeftig (133), unter die zur Spitze (159).
     */
    val bewegterPuls: List<Pair<Long, Int>> = pulsAus(pulsJeHalbeStunde.toMutableList().also { it[26] = 146; it[27] = 154; it[28] = 118 })

    /**
     * Die Zonen der Herzseite: Ruhepuls 55 (der Median der Naechte im Stand unten
     * und der letzten) und Maximalpuls 185 - Grenzen 107, 133, 159.
     */
    val pulsZonen: List<Int> = Gesundheitsrechnung.pulsZonen(55, 185)

    /** Die Schritte je halbe Stunde ab 6:00 - die Hoehen des Entwurfs, 45 Schritte je Pixel. */
    val schrittBins: List<Pair<Long, Long>> = listOf(4, 16, 20, 10, 8, 6, 12, 6, 10, 34, 30, 8, 14, 6, 10, 4)
        .mapIndexed { i, px -> um(6, 0).epochSecond + i * 1800L to px * 45L }

    private fun sitzung(beginn: Instant, minuten: Long, art: Int, titel: String) = ExerciseSessionRecord(
        startTime = beginn, startZoneOffset = null, endTime = beginn.plusSeconds(minuten * 60), endZoneOffset = null,
        metadata = Kieselsport.vonDerUhr("beispiel-${beginn.epochSecond}"), exerciseType = art, title = titel,
    )

    val spaziergang = TrainingEintrag(sitzung(um(10, 30), 50, ExerciseSessionRecord.EXERCISE_TYPE_WALKING, "Walk"), Sport.Wandern, true, 2700.0)
    private val fahrt = TrainingEintrag(sitzung(um(17, 5, heute.minusDays(1)), 62, ExerciseSessionRecord.EXERCISE_TYPE_BIKING, "Ride"), Sport.Bike, true, 24_300.0)
    private val lauf3 = TrainingEintrag(sitzung(um(7, 10, heute.minusDays(8)), 41, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Run"), Sport.Laufen, true, 7_800.0)

    /** Die Uebersicht der Sportseite: neuestes zuerst. */
    val sport = TrainingsUebersicht(true, listOf(spaziergang, fahrt, lauf3), emptyList())

    // Mit den Kennungen, die SupCycle in die Akte schreibt: an ihnen findet Stack die Einnahmezeit.
    private val praeparate = listOf("Multivitamin", "Zinc", "D3").mapIndexed { i, name ->
        Tageseintrag(um(8, 0).plusSeconds(i * 10L), true, name, null, id = "p$i", eigen = true, kennung = SupCycle.kennung(heute, i, name))
    }

    /** Das Wasser der Woche nach dem Entwurf "Erlebnis-Wasser" (2.6, 2.1, 2.7, 2.5, 1.8, 2.9 l), heute 0.5 l. */
    val wasserWoche: List<Pair<LocalDate, Int>> = listOf(2600, 2100, 2700, 2500, 1800, 2900, 500)
        .mapIndexed { i, ml -> heute.minusDays(6L - i) to ml }

    val stand = GesundheitsStand(
        schritte = 3860, aktivMinuten = 54, meter = 2950.0,
        wocheSchritte = (6 downTo 0).map { heute.minusDays(it.toLong()) to listOf(9120L, 6400, 11250, 7300, 8800, 10400, 3860)[6 - it] },
        nacht = nacht,
        ruhepulsWoche = listOf(54, 56, 55, 55, 54, 56),
        wocheSchlaf = (6 downTo 0).map { heute.minusDays(it.toLong()) to listOf(452, 431, 470, 418, 465, 440, 479)[6 - it] },
        puls = bandPuls.filter { it.first >= jetzt.epochSecond - 86_400 },
        spo2 = emptyList(),
        wasserMl = 500,
        glaeser = listOf(um(9, 20), um(12, 10)),
        wasserZiel = Drinktervall.Ziel(10, 250),
        supplemente = SupCycle.Stand(
            heute, listOf("Multivitamin", "Zinc", "D3", "Magnesium"), 0b1111, 0b0111,
            listOf(
                SupCycle.Platz("Multivitamin", LocalTime.of(8, 0), true, 1, 0, 0, 0),
                // Zink als Kur wie Creatine im Entwurf: acht Wochen Einnahme, vier Pause, heute in der dritten Woche.
                SupCycle.Platz("Zinc", LocalTime.of(8, 0), true, 1, 8, 4, heute.toEpochDay() - 16),
                SupCycle.Platz("D3", LocalTime.of(8, 0), true, 1, 0, 0, 0),
                SupCycle.Platz("Magnesium", LocalTime.of(20, 0), true, 1, 0, 0, 0),
            ),
            SupCycle.PLANFASSUNG_KALENDER,
        ),
        koffein = listOf(um(6, 44) to 63.0),
        eintraege = (praeparate + Tageseintrag(um(6, 44), false, "Espresso, sugar", 63.0, id = "k1", eigen = true, kennung = "koffein-1"))
            .sortedByDescending { it.zeit },
        glasEintraege = listOf(Glaseintrag(um(9, 20), "g1", true, 250), Glaseintrag(um(12, 10), "g2", true, 250)),
        letzteDaten = jetzt.minusSeconds(120),
        bandPuls = bandPuls,
        pulsZonen = pulsZonen,
        schrittBins = schrittBins,
        wasserWoche = wasserWoche,
    )

    /** Wohin die Seite fuehrt - im Bild nirgendwohin. */
    val wege = Gesundheitswege({}, {}, {}, {}, {}, {}, {}, {})
}
