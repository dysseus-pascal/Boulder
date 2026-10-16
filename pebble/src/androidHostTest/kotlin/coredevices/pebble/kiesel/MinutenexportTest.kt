package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinutenexportTest {
    private val zuerich = ZoneId.of("Europe/Zurich")

    // 16.10.2026, 14:00 in Zuerich (Sommerzeit, UTC+2).
    private val von = 1_792_152_000L

    private fun minute(t: Long, schritte: Int, vmc: Int, puls: Int, lage: Int) = HealthDataEntity(
        timestamp = t, steps = schritte, orientation = lage, intensity = vmc, lightIntensity = 0,
        activeMinutes = 0, restingGramCalories = 0, activeGramCalories = 0, distanceCm = 0, heartRate = puls,
    )

    @Test
    fun csvWieDerNachtexportMitMarkierungenUndDatum() {
        val minuten = listOf(
            minute(von + 180, 7, 700, 110, 56),
            minute(von - 60, 90, 2600, 92, 65),  // davor: faellt weg
            minute(von, 3, 812, 104, 55),
            minute(von + 60, 0, 1290, 106, 55),
            minute(von + 240, 5, 900, 108, 55),  // das Ende gehoert nicht dazu
        )
        val markierungen = listOf(
            Minutenexport.Markierung("sitzung", von + 120, von + 1800, listOf("typ" to "OpenWorkout", "manuell" to 0, "schritte" to 12)),
            Minutenexport.Markierung("training", von - 600, von + 180, listOf("sport" to "Bike", "eigen" to 1)),
        )
        assertEquals(
            """
            # minuten von=1792152000 bis=1792152240 ruhepuls=51 firmware=v4.41.1-b31
            # training von=1792151400 bis=1792152180 uhrzeit=13:50-14:03 sport=Bike eigen=1
            # sitzung von=1792152120 bis=1792153800 uhrzeit=14:02-14:30 typ=OpenWorkout manuell=0 schritte=12
            minute,uhrzeit,schritte,vmc,puls,lage,datum
            0,14:00,3,812,104,55,2026-10-16
            1,14:01,0,1290,106,55,2026-10-16
            3,14:03,7,700,110,56,2026-10-16

            """.trimIndent(),
            Minutenexport.csv(von, von + 240, 51, "v4.41.1-b31", minuten, markierungen, zuerich),
        )
    }

    @Test
    fun ueberMitternachtWechseltDasDatum() {
        val spaet = 1_792_187_880L  // 23:58
        val text = Minutenexport.csv(
            spaet, spaet + 180, null, null,
            listOf(minute(spaet, 0, 0, 0, 1), minute(spaet + 60, 0, 0, 0, 1), minute(spaet + 120, 0, 0, 0, 1)),
            emptyList(), zuerich,
        )
        assertEquals(
            listOf(
                "# minuten von=1792187880 bis=1792188060 ruhepuls=- firmware=-",
                "minute,uhrzeit,schritte,vmc,puls,lage,datum",
                "0,23:58,0,0,0,1,2026-10-16",
                "1,23:59,0,0,0,1,2026-10-16",
                "2,00:00,0,0,0,1,2026-10-17",
            ),
            text.trimEnd().lines(),
        )
    }

    @Test
    fun zeitraumMussVorwaertsGehenUndHoechstensSiebenTage() {
        assertEquals(Minutenexport.Fehler.ENDE_VOR_BEGINN, Minutenexport.pruefe(von, von))
        assertEquals(Minutenexport.Fehler.ENDE_VOR_BEGINN, Minutenexport.pruefe(von, von - 60))
        assertNull(Minutenexport.pruefe(von, von + 60))
        assertNull(Minutenexport.pruefe(von, von + 7 * 86_400L))
        assertEquals(Minutenexport.Fehler.ZU_LANG, Minutenexport.pruefe(von, von + 7 * 86_400L + 60))
    }

    @Test
    fun umTrainingFuenfzehnMinutenDavorUndDanachAufGanzeMinuten() {
        // 14:20:30-15:05:10 wird 14:05-15:21: der Rand nach aussen gerundet, nichts vom Training faellt weg.
        assertEquals(1_792_152_300L to 1_792_156_860L, Minutenexport.umTraining(1_792_153_230L, 1_792_155_910L))
        assertEquals(1_792_152_300L to 1_792_156_800L, Minutenexport.umTraining(1_792_153_200L, 1_792_155_900L))
    }

    @Test
    fun dateinameNachOrtszeit() {
        assertEquals("minuten-2026-10-16-1400.csv", Minutenexport.dateiname(von, zuerich))
    }
}
