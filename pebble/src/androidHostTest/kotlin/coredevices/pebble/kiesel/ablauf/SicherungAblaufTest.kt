package coredevices.pebble.kiesel.ablauf

import android.content.Context
import android.location.Location
import coredevices.pebble.kiesel.Ablage
import coredevices.pebble.kiesel.Sichern
import coredevices.pebble.kiesel.Sicherungsformat
import coredevices.pebble.kiesel.Spur
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Die Sicherung im gewaehlten Ordner: eine abgebrochene Sicherung laesst eine
 * lesbare zurueck, und eine Spur, die beim Sichern noch lief, wird ergaenzt.
 *
 * Der Ordner ist ein Nachbau im Speicher, wie ihn das Storage Access Framework
 * zeigt: Schreiben ueberschreibt ("wt"), und bricht der Prozess mitten im
 * Schreiben ab, liegt nur der Anfang da.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SicherungAblaufTest {
    private lateinit var context: Context

    private class ProzessEnde : Error("Prozess beendet")

    /** [stirbBeim]: beim wievielten Schreiben (ab 1) der Prozess endet, nach der Haelfte der Bytes. */
    private class Ordner(var stirbBeim: Int = 0) : Ablage {
        val dateien = mutableMapOf<String, String>()
        var geschrieben = 0

        override fun lege(pfad: String, inhalt: String, typ: String): Boolean {
            geschrieben++
            if (geschrieben == stirbBeim) {
                dateien[pfad] = inhalt.substring(0, inhalt.length / 2)
                throw ProzessEnde()
            }
            dateien[pfad] = inhalt
            return true
        }

        override fun hole(pfad: String): String? = dateien[pfad]
        override fun weg(pfad: String): Boolean {
            dateien.remove(pfad)
            return true
        }
    }

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun stand(tag: LocalDate, schritte: Double) =
        Sicherungsformat.alsJson(listOf(tag to mapOf("schritte" to schritte)), emptyList(), tag.atStartOfDay(ZoneOffset.UTC).toInstant().toString())

    @Test
    fun eine_abgebrochene_sicherung_laesst_eine_lesbare_zurueck() {
        val gestern = LocalDate.of(2026, 10, 3)
        val heute = LocalDate.of(2026, 10, 4)
        // Jeder Abbruchpunkt: beim ersten, zweiten oder dritten Schreiben.
        for (stirb in 1..3) {
            val o = Ordner()
            assertTrue(Sichern.legeSicher(o, Sicherungsformat.BOULDER, stand(gestern, 1000.0), "application/json"))
            o.geschrieben = 0
            o.stirbBeim = stirb
            runCatching { Sichern.legeSicher(o, Sicherungsformat.BOULDER, stand(heute, 2000.0), "application/json") }
                .onSuccess { if (stirb <= 2) error("Abbruch $stirb nicht ausgeloest") }
                .onFailure { assertTrue(it is ProzessEnde) }
            val gelesen = assertNotNull(Sichern.holeStand(o), "Nach Abbruch beim Schreiben $stirb keine lesbare Sicherung")
            assertTrue(gelesen.tage.single().first in setOf(gestern, heute))
        }
    }

    @Test
    fun die_juengere_von_beiden_gilt() {
        val o = Ordner()
        o.dateien[Sicherungsformat.BOULDER] = stand(LocalDate.of(2026, 10, 3), 1000.0)
        o.dateien[Sichern.zweitname(Sicherungsformat.BOULDER)] = stand(LocalDate.of(2026, 10, 4), 2000.0)
        assertEquals(LocalDate.of(2026, 10, 4), Sichern.holeStand(o)!!.tage.single().first)
        assertEquals("boulder-neu.json", Sichern.zweitname(Sicherungsformat.BOULDER))
        assertEquals("spuren/spur-7-neu.jsonl", Sichern.zweitname("spuren/spur-7.jsonl"))
    }

    private fun ort(t: Long) = Location("gps").apply {
        time = t * 1000
        latitude = 47.0 + t * 1e-5
        longitude = 8.0
        accuracy = 5f
    }

    @Test
    fun eine_spur_die_beim_sichern_noch_lief_wird_ergaenzt() {
        val b = 1_790_000_000L
        Spur.haengeAn(context, b, listOf(ort(b), ort(b + 10)))
        val o = Ordner()
        assertEquals(1, Sichern.sichereSpuren(context, o, "spuren@test", Spur.alle(context)))
        val pfad = "spuren/spur-$b.jsonl"
        assertEquals(2, o.dateien.getValue(pfad).lines().count { it.isNotBlank() })

        // Die Aufnahme lief weiter.
        Spur.haengeAn(context, b, listOf(ort(b + 20), ort(b + 30)))
        assertEquals(1, Sichern.sichereSpuren(context, o, "spuren@test", Spur.alle(context)))
        assertEquals(4, o.dateien.getValue(pfad).lines().count { it.isNotBlank() })
        // Unveraendert wird nicht noch einmal geschrieben.
        assertEquals(0, Sichern.sichereSpuren(context, o, "spuren@test", Spur.alle(context)))
        // Und die Zweitdatei liegt nicht herum.
        assertEquals(setOf(pfad), o.dateien.keys)
    }

    @Test
    fun eine_spur_mit_halber_hauptdatei_kommt_ganz_zurueck() {
        val b = 1_790_000_000L
        Spur.haengeAn(context, b, listOf(ort(b), ort(b + 10)))
        val o = Ordner()
        Sichern.sichereSpuren(context, o, "spuren@test", Spur.alle(context))
        Spur.haengeAn(context, b, listOf(ort(b + 20), ort(b + 30)))
        // Der Prozess endet beim Schreiben der Hauptdatei (das zweite Schreiben).
        o.geschrieben = 0
        o.stirbBeim = 2
        assertFailsWith<ProzessEnde> { Sichern.sichereSpuren(context, o, "spuren@test", Spur.alle(context)) }
        assertEquals(4, assertNotNull(Sichern.holeSpur(o, b)).lines().count { it.isNotBlank() })
    }
}
