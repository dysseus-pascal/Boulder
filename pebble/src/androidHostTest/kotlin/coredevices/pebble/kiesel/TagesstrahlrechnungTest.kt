package coredevices.pebble.kiesel

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagesstrahlrechnungTest {
    private val zone = ZoneOffset.UTC
    private val heute = LocalDate.of(2026, 10, 2)
    private fun um(stunde: Int, minute: Int = 0, sekunde: Int = 0): Instant = heute.atTime(stunde, minute, sekunde).toInstant(zone)

    private fun training(beginn: Instant): TrainingEintrag {
        val sitzung = ExerciseSessionRecord(
            startTime = beginn, startZoneOffset = null, endTime = beginn.plusSeconds(1800), endZoneOffset = null,
            metadata = Kieselsport.vonDerUhr("probe-${beginn.epochSecond}"),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        )
        return TrainingEintrag(sitzung, Sport.Laufen, true, 5000.0)
    }

    private fun getraenk(zeit: Instant, name: String = "Coffee") = Tageseintrag(zeit, false, name, 95.0, id = name, eigen = true)
    private fun praeparat(zeit: Instant, name: String, eigen: Boolean = true) = Tageseintrag(zeit, true, name, null, id = name, eigen = eigen)

    private fun stand(
        glaeser: List<Glaseintrag> = emptyList(), eintraege: List<Tageseintrag> = emptyList(),
        mitDrinktervall: Boolean = true, mitSupCycle: Boolean = true,
    ) = GesundheitsStand(
        schritte = 0, aktivMinuten = 0, meter = 0.0, wocheSchritte = emptyList(), nacht = null,
        ruhepulsWoche = emptyList(), wocheSchlaf = emptyList(), puls = emptyList(), spo2 = emptyList(),
        wasserMl = 0, glaeser = emptyList(), wasserZiel = null, supplemente = null, koffein = emptyList(), letzteDaten = null,
        mitDrinktervall = mitDrinktervall, mitSupCycle = mitSupCycle, eintraege = eintraege, glasEintraege = glaeser,
    )

    @Test
    fun alles_von_heute_nach_der_zeit() {
        val lauf = training(um(7, 30))
        val glas = Glaseintrag(um(9), "g1", eigen = true)
        val kaffee = getraenk(um(8))
        val zink = praeparat(um(6, 45), "Zinc")
        val e = Tagesstrahlrechnung.ereignisse(stand(listOf(glas), listOf(kaffee, zink)), listOf(lauf), heute, zone)
        assertEquals(
            listOf(
                Tagesstrahlrechnung.Ereignis.Praeparate(listOf(zink)),
                Tagesstrahlrechnung.Ereignis.Training(lauf),
                Tagesstrahlrechnung.Ereignis.Getraenk(kaffee),
                Tagesstrahlrechnung.Ereignis.Wasser(glas),
            ),
            e,
        )
    }

    @Test
    fun trainings_nur_die_heute_begannen() {
        // Ein Lauf ueber Mitternacht gehoert zu gestern - er begann gestern.
        val gestern = training(um(23, 40).minusSeconds(86_400))
        val frueh = training(um(0, 10))
        val e = Tagesstrahlrechnung.ereignisse(stand(), listOf(gestern, frueh), heute, zone)
        assertEquals(listOf(Tagesstrahlrechnung.Ereignis.Training(frueh)), e)
    }

    @Test
    fun praeparate_einer_minute_sind_eine_zeile_fremde_daneben() {
        // SupCycle hakt drei in einem Zug ab, Sekunden auseinander; eine fremde App traegt in derselben Minute ein.
        val a = praeparat(um(8, 0, 1), "Multivitamin")
        val b = praeparat(um(8, 0, 20), "Creatine")
        val c = praeparat(um(8, 0, 59), "Zinc")
        val fremd = praeparat(um(8, 0, 30), "Fish oil", eigen = false)
        val spaeter = praeparat(um(8, 1, 0), "Magnesium")
        val e = Tagesstrahlrechnung.ereignisse(stand(eintraege = listOf(a, b, fremd, c, spaeter)), emptyList(), heute, zone)
        val zeilen = e.map { it as Tagesstrahlrechnung.Ereignis.Praeparate }
        assertEquals(listOf("Multivitamin, Creatine, Zinc", "Fish oil", "Magnesium"), zeilen.map { it.titel })
        assertEquals(listOf(true, false, true), zeilen.map { it.eigen })
        // Die Zeile steht zur Zeit ihres ersten Eintrags.
        assertEquals(um(8, 0, 1), zeilen.first().zeit)
    }

    @Test
    fun ohne_uhr_apps_weder_getraenke_noch_praeparate() {
        val glas = Glaseintrag(um(9), "g1", eigen = true)
        val eintraege = listOf(getraenk(um(8)), praeparat(um(8), "Zinc"))
        val ohneDrinktervall = Tagesstrahlrechnung.ereignisse(stand(listOf(glas), eintraege, mitDrinktervall = false), emptyList(), heute, zone)
        assertTrue(ohneDrinktervall.all { it is Tagesstrahlrechnung.Ereignis.Praeparate })
        val ohneSupCycle = Tagesstrahlrechnung.ereignisse(stand(listOf(glas), eintraege, mitSupCycle = false), emptyList(), heute, zone)
        assertTrue(ohneSupCycle.none { it is Tagesstrahlrechnung.Ereignis.Praeparate })
        assertEquals(2, ohneSupCycle.size)
    }

    @Test
    fun gleiche_zeit_training_vor_wasser_vor_getraenk_vor_praeparat() {
        val zeit = um(12)
        val lauf = training(zeit)
        val glas = Glaseintrag(zeit, "g1", eigen = false, ml = 250)
        val kaffee = getraenk(zeit)
        val zink = praeparat(zeit, "Zinc")
        val e = Tagesstrahlrechnung.ereignisse(stand(listOf(glas), listOf(zink, kaffee)), listOf(lauf), heute, zone)
        assertEquals(
            listOf(
                Tagesstrahlrechnung.Ereignis.Training::class, Tagesstrahlrechnung.Ereignis.Wasser::class,
                Tagesstrahlrechnung.Ereignis.Getraenk::class, Tagesstrahlrechnung.Ereignis.Praeparate::class,
            ),
            e.map { it::class },
        )
    }
}
