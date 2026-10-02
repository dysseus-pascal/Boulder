package coredevices.pebble.kiesel

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import coredevices.pebble.health.Anfragen
import coredevices.pebble.health.syncNachFrage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RechteTest {
    private val hintergrund = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    private val verlauf = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    private val wasser = "android.permission.health.WRITE_HYDRATION"

    @Test
    fun ohne_funktion_kein_recht() {
        val rechte = setOf(wasser, hintergrund, verlauf)
        assertEquals(setOf(wasser), nurVerfuegbare(rechte) { false })
        assertEquals(rechte, nurVerfuegbare(rechte) { true })
        // Nur den Verlauf kennt diese Akte
        assertEquals(
            setOf(wasser, verlauf),
            nurVerfuegbare(rechte) { it == HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY },
        )
    }

    @Test
    fun funktionsrechte_stehen_in_den_kiesel_rechten() {
        // Sonst filterte nurVerfuegbare etwas, das nie gefragt wird
        assertTrue(KIESEL_RECHTE.containsAll(FUNKTIONSRECHTE.keys))
    }

    @Test
    fun startdialog_schaltet_nur_ein() {
        assertEquals(true, syncNachFrage(erteilt = true, nurEinschalten = true))
        assertNull(syncNachFrage(erteilt = false, nurEinschalten = true))
        // Der Schalter in den Einstellungen wie bei Gravel
        assertEquals(true, syncNachFrage(erteilt = true, nurEinschalten = false))
        assertEquals(false, syncNachFrage(erteilt = false, nurEinschalten = false))
    }

    @Test
    fun einmal_je_fassung() {
        val fehlend = setOf(wasser)
        assertTrue(Rechtefrage.sollFragen(fehlend, null, "0.41.0"))
        assertTrue(Rechtefrage.sollFragen(fehlend, "0.40.0", "0.41.0"))
        assertFalse(Rechtefrage.sollFragen(fehlend, "0.41.0", "0.41.0"))
        assertFalse(Rechtefrage.sollFragen(emptySet(), null, "0.41.0"))
    }

    @Test
    fun abgeloeste_anfrage_ist_keine_ablehnung() = runTest {
        val anfragen = Anfragen<Set<String>>()
        val (alteNummer, alte) = anfragen.neu()
        val wartet = async { alte.await() }
        val (neueNummer, neue) = anfragen.neu()
        // Die alte endet ohne Antwort, nicht mit "nichts erteilt"
        assertFailsWith<CancellationException> { wartet.await() }
        assertTrue(neue.isActive)
        // Die Antwort des alten Dialogs beantwortet die neue Anfrage nicht
        anfragen.beantworte(alteNummer, emptySet())
        assertTrue(neue.isActive)
        anfragen.beantworte(neueNummer, setOf(wasser))
        assertEquals(setOf(wasser), neue.await())
    }

    @Test
    fun zweite_antwort_faellt_weg() = runTest {
        val anfragen = Anfragen<Set<String>>()
        val (nummer, antwort) = anfragen.neu()
        anfragen.beantworte(nummer, setOf(wasser))
        anfragen.beantworte(nummer, emptySet())
        assertEquals(setOf(wasser), antwort.await())
    }
}
