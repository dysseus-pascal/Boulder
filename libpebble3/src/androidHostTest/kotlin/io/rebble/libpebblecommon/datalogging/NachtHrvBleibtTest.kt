package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.testBoulderDatenbank
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KEINE LEERE HRV UEBER EINE GEMESSENE (0.52). Bis Firmware b24 lagen die
 * HRV-Fenster nur im RAM der Uhr; nach einem Firmware-Update am Morgen kam
 * dieselbe Nacht (gleicher Beginn) ohne HRV noch einmal, und das Ersetzen
 * nahm hrv = 0 mit. Jetzt bleibt die gespeicherte HRV, alles andere ersetzt
 * die neue Fassung, und eine neue HRV ueber 0 ersetzt die alte.
 *
 * Der ganze Weg wie auf dem Telefon: Saetze auf Tag 88 durch den
 * HealthDataProcessor in die echte Room-Datenbank im Speicher. Dazu das
 * bedingte Eintragen der Reparatur (setzeHrvWennLeer) an derselben Datenbank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NachtHrvBleibtTest {
    private var db: Database? = null
    private var boulder: BoulderDatenbank? = null

    @AfterTest
    fun zu() {
        db?.close()
        boulder?.close()
    }

    // Die Nacht ab 30.9.2026, 22:00 Ortszeit - die vom Do 1.10. auf dem Telefon.
    private val beginn = 1_790_798_400L

    /** Ein Satz auf Tag 88 (Fassung 3 mit Akku und Messstufe), ganz und in einem Teil, mit diesen Kopfwerten. */
    private fun nachtSatz(hrv: Int, aufwachen: Int = 400, schlaf: Int = 380, ruhepuls: Int = 50, phase: Int = 1): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(3); u16(328); u32(beginn); u32(7200)
        u16(10); u16(aufwachen); u16(schlaf); u8(ruhepuls); u8(0); u8(1); u16(hrv)
        u8(1); u16(10); u16(aufwachen); u8(phase)
        repeat(59) { repeat(5) { u8(0) } }
        u8(80); u8(70); u8(0); u8(4)
        return b.toByteArray()
    }

    private class Lauf(val verarbeitung: HealthDataProcessor, val naechte: BoulderDatenbank)

    private fun TestScope.lauf(): Lauf {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val naechte = testBoulderDatenbank(coroutineContext[CoroutineDispatcher]!!).also { boulder = it }
        val verarbeitung = HealthDataProcessor(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            datenbank.healthDao(), datenbank.healthStatDao(), datenbank.spo2Dao(), naechte.nachtDao(),
            object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_840_000) },
        )
        return Lauf(verarbeitung, naechte)
    }

    private suspend fun Lauf.schicke(satz: ByteArray) {
        verarbeitung.speichere(1u, 88u, SYSTEM_APP_UUID, 328u, satz)
    }

    private suspend fun Lauf.nacht(): NachtEntity = assertNotNull(naechte.nachtDao().nacht(beginn))

    @Test
    fun dieselbeNachtOhneHrvLaesstDieGemesseneStehen() = runTest {
        val l = lauf()
        l.schicke(nachtSatz(hrv = 53))
        assertEquals(53, l.nacht().hrv)

        // Nach dem Firmware-Update: neu gerechnet, ohne HRV, sonst andere Werte.
        l.schicke(nachtSatz(hrv = 0, aufwachen = 420, schlaf = 395, ruhepuls = 52, phase = 3))
        val n = l.nacht()
        assertEquals(53, n.hrv, "die gemessene HRV ging verloren")
        // Alle anderen Felder und die Abschnitte kommen aus der neuen Fassung.
        assertEquals(listOf(420, 395, 52), listOf(n.aufwachen, n.schlafMinuten, n.ruhepuls))
        assertEquals(listOf(Triple(10, 420, 3)), l.naechte.nachtDao().abschnitte(beginn).map { Triple(it.von, it.bis, it.phase) })
        assertEquals(1, l.naechte.nachtDao().naechte(0, Long.MAX_VALUE).size)
    }

    @Test
    fun eineNeueGemesseneHrvErsetztDieAlte() = runTest {
        val l = lauf()
        l.schicke(nachtSatz(hrv = 53))
        l.schicke(nachtSatz(hrv = 61, aufwachen = 430))
        assertEquals(61, l.nacht().hrv)
        assertEquals(430, l.nacht().aufwachen)
    }

    @Test
    fun ohneGespeicherteHrvBleibtEsBeiKeiner() = runTest {
        val l = lauf()
        l.schicke(nachtSatz(hrv = 0))
        l.schicke(nachtSatz(hrv = 0, aufwachen = 410))
        assertEquals(0, l.nacht().hrv)
        // Die erste Fassung mit HRV nach einer ohne: die gemessene gilt.
        l.schicke(nachtSatz(hrv = 47, aufwachen = 415))
        assertEquals(47, l.nacht().hrv)
    }

    /**
     * Das Eintragen der Reparatur: nur in eine Nacht ohne HRV, nie ueber eine
     * gemessene, und keine neue Nacht, wo keine ist.
     */
    @Test
    fun dieReparaturTraegtNurInEineNachtOhneHrvEin() = runTest {
        val l = lauf()
        val dao = l.naechte.nachtDao()
        val ohne = NachtEntity(beginn, 7200, 10, 400, 380, 50, 0)
        val mit = ohne.copy(beginnUtc = beginn + 86_400, hrv = 40)
        dao.speichereNacht(ohne)
        dao.speichereNacht(mit)

        assertEquals(1, dao.setzeHrvWennLeer(beginn, 53))
        assertEquals(ohne.copy(hrv = 53), dao.nacht(beginn))
        assertEquals(0, dao.setzeHrvWennLeer(beginn + 86_400, 61))
        assertEquals(40, dao.nacht(beginn + 86_400)?.hrv)
        // Ein zweites Mal aendert die reparierte nicht mehr.
        assertEquals(0, dao.setzeHrvWennLeer(beginn, 70))
        assertEquals(53, dao.nacht(beginn)?.hrv)
        assertEquals(0, dao.setzeHrvWennLeer(beginn + 2 * 86_400, 61))
        assertNull(dao.nacht(beginn + 2 * 86_400))
        assertEquals(2, dao.naechte(0, Long.MAX_VALUE).size)
    }
}
