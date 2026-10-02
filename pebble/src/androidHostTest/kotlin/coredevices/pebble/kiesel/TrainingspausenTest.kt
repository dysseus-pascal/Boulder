package coredevices.pebble.kiesel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Pausen eines Trainings: aus den Meldungen der Uhr, gegen ihre Dauer
 * geprueft, und die aktive Sekunde der Uhr als Uhrzeit.
 */
class TrainingspausenTest {
    private val anfang = 1_000_000L
    private fun p(t: Long) = true to anfang + t
    private fun w(t: Long) = false to anfang + t

    @Test
    fun meldungenWerdenPausen() {
        // Fahrt 10:00-11:20: 30 min, 20 min Kaffee, 30 min, dann die Pause zum Speichern.
        val plan = Trainingspausen.ausMeldungen(listOf(p(1800), w(3000), p(4800)))
        assertEquals(listOf(Trainingspausen.Pause(anfang + 1800, anfang + 3000)), plan.pausen)
        assertEquals(anfang + 4800, plan.offen)
    }

    @Test
    fun wiederholteMeldungenZaehlenEinmal() {
        // Die Uhr wiederholt, was sie fuer verloren haelt: zwei Pausen, zwei Weiter.
        val plan = Trainingspausen.ausMeldungen(listOf(p(100), p(102), w(200), w(201), p(500)))
        assertEquals(listOf(Trainingspausen.Pause(anfang + 100, anfang + 200)), plan.pausen)
        assertEquals(anfang + 500, plan.offen)
        assertFalse(plan.bruch)
    }

    @Test
    fun eineFehlendeMeldungIstEinBruch() {
        // Das Weiter nach der kurzen Pause bei 600 ging verloren: aus p600 ... w3000
        // wuerde sonst eine Pause von 40 min, und alles danach rutschte um sie.
        val plan = Trainingspausen.ausMeldungen(listOf(p(600), p(1800), w(3000)))
        assertTrue(plan.bruch)
        assertEquals(emptyList(), Trainingspausen.stimmig(plan, anfang, 3600, anfang + 9000).pausen)
        // Ein Weiter ohne Pause, das keine Wiederholung ist: die Pause fehlt.
        assertTrue(Trainingspausen.ausMeldungen(listOf(w(50))).bruch)
        assertTrue(Trainingspausen.ausMeldungen(listOf(p(100), w(200), w(900))).bruch)
        // Beim Nachtragen gilt dann nur noch die letzte Pause.
        val nach = Trainingspausen.ohneBruch(Trainingspausen.ausMeldungen(listOf(w(50), p(4800))))
        assertEquals(Trainingspausen.Plan(emptyList(), anfang + 4800), nach)
    }

    @Test
    fun dieAktiveZeitEndetNichtVorDemLetztenWeiter() {
        // Die letzte Pause kam nicht an. Bekannt sind 10 min Pause bei 50 min -
        // bei nur 30 min aktiv kann das Weiter aber nicht bei 60 min liegen.
        val plan = Trainingspausen.ausMeldungen(listOf(p(3000), w(3600)))
        assertEquals(emptyList(), Trainingspausen.stimmig(plan, anfang, 1800, anfang + 9000).pausen)
    }

    @Test
    fun kaputteZeilenFallenWeg() {
        val m = Trainingspausen.meldungenAus(listOf("p,1000100", "w,", "x,5", "w,1000200", ""))
        assertEquals(listOf(true to 1_000_100L, false to 1_000_200L), m)
    }

    @Test
    fun aktiveSekundeAlsUhrzeit() {
        val pausen = listOf(Trainingspausen.Pause(anfang + 1800, anfang + 3000))
        // Vor der Pause unveraendert, die Sekunde der Pause selbst noch davor.
        assertEquals(anfang + 600, Trainingspausen.wand(pausen, anfang, 600))
        assertEquals(anfang + 1800, Trainingspausen.wand(pausen, anfang, 1800))
        // Danach um 20 min spaeter: Sekunde 2400 der Uhr ist 10:50, nicht 10:40.
        assertEquals(anfang + 3600, Trainingspausen.wand(pausen, anfang, 2400))
        assertEquals(anfang + 4800, Trainingspausen.wand(pausen, anfang, 3600))
    }

    @Test
    fun zweiPausenSchiebenZusammen() {
        val pausen = listOf(
            Trainingspausen.Pause(anfang + 600, anfang + 900),     // 5 min nach 10 min
            Trainingspausen.Pause(anfang + 1500, anfang + 1560),   // 1 min nach weiteren 10 min
        )
        assertEquals(anfang + 1200 + 300, Trainingspausen.wand(pausen, anfang, 1200))
        assertEquals(anfang + 1800 + 360, Trainingspausen.wand(pausen, anfang, 1800))
    }

    @Test
    fun stimmigePausenBleiben() {
        // 60 min aktiv, 20 min Pause: die letzte Pause liegt bei 80 min (Meldung 20 s spaet).
        val plan = Trainingspausen.ausMeldungen(listOf(p(1800), w(3000), p(4820)))
        val gut = Trainingspausen.stimmig(plan, anfang, 3600, anfang + 4900)
        assertEquals(plan, gut)
    }

    @Test
    fun einVerlorenesWeiterVerwirftDiePausen() {
        // Das Weiter nach dem Kaffee kam nie an: zwei Pausen ohne Weiter. Die
        // Pausen gelten nicht, die juengste bleibt das Ende der Spur.
        val plan = Trainingspausen.ausMeldungen(listOf(p(1800), p(4800)))
        val gut = Trainingspausen.stimmig(plan, anfang, 3600, anfang + 4900)
        assertEquals(emptyList(), gut.pausen)
        assertEquals(anfang + 4800, gut.offen)
        // Lag die juengste vor dem Ende der aktiven Zeit, taugt auch sie nicht.
        assertNull(Trainingspausen.stimmig(plan, anfang, 5400, anfang + 9000).offen)
    }

    @Test
    fun eineVerlorenePauseVerwirftDieAnderen() {
        // Die Kaffeepause kam nicht an, nur eine kurze spaeter: die Summe passt nicht.
        val plan = Trainingspausen.ausMeldungen(listOf(p(3300), w(3360), p(4860)))
        val gut = Trainingspausen.stimmig(plan, anfang, 3600, anfang + 4900)
        assertEquals(emptyList(), gut.pausen)
        // Die letzte Pause liegt hinter der aktiven Zeit - an ihr wurde gespeichert.
        assertEquals(anfang + 4860, gut.offen)
    }

    @Test
    fun ohneLetztePauseZaehltDieSummeGegenJetzt() {
        val plan = Trainingspausen.ausMeldungen(listOf(p(1800), w(3000)))
        assertEquals(plan, Trainingspausen.stimmig(plan, anfang, 3600, anfang + 4900))
        // Kaeme die Zusammenfassung vor dem Ende, das die Pausen ergeben, stimmt etwas nicht.
        assertEquals(emptyList(), Trainingspausen.stimmig(plan, anfang, 3600, anfang + 4000).pausen)
    }
}
