package coredevices.pebble.health

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der blockweise Sync muss jede Zeile nach dem Cursor genau einmal und in
 * Zeitfolge abgeben - keine Minute uebersprungen, keine doppelt - und darf
 * nie mehr als einen Tag auf einmal lesen.
 */
class TagesbloeckeTest {

    /** Zeilen als Zeitstempel; Abfragen wie die DAO: [von, bis). */
    private class Tabelle(val zeiten: List<Long>) {
        var lesungen = 0
        var proben = 0
        val bloecke = mutableListOf<List<Long>>()

        suspend fun lauf(nach: Long, letzte: Long = zeiten.maxOrNull() ?: 0, scheitertAb: Int = Int.MAX_VALUE) =
            inTagesbloecken(
                nach = nach,
                letzte = letzte,
                hatZeilen = { von, bis -> proben++; zeiten.any { it in von until bis } },
                lies = { von, bis -> lesungen++; zeiten.filter { it in von until bis }.sorted() },
            ) { block ->
                if (bloecke.size >= scheitertAb) return@inTagesbloecken false
                bloecke += block
                true
            }
    }

    private val start = 1_790_000_000L
    private fun minuten(von: Long, anzahl: Int) = List(anzahl) { von + it * 60L }

    @Test
    fun alltagIstEinBlockWieBisher() = runTest {
        val alt = minuten(start, 1000)
        val neu = minuten(start + 1000 * 60, 15)
        val t = Tabelle(alt + neu)
        t.lauf(nach = alt.last())
        assertEquals(listOf(neu), t.bloecke)
        assertEquals(1, t.lesungen)
        assertEquals(0, t.proben)
    }

    @Test
    fun cursorNullMitNeunzigTagenGibtJedeMinuteGenauEinmal() = runTest {
        // 90 Tage minuetlich, mit zwei Tagen Luecke (Uhr leer) mittendrin.
        val zeilen = minuten(start, 40 * 1440) + minuten(start + 42 * BLOCK_SEKUNDEN, 48 * 1440)
        val t = Tabelle(zeilen)
        t.lauf(nach = 0)
        assertEquals(zeilen, t.bloecke.flatten())
        assertTrue(t.bloecke.all { it.size <= 1440 })
        // Leere Jahrzehnte vor den Daten kosten eine Halbierungssuche, nicht ~20 000 Lesungen.
        assertTrue(t.lesungen <= 100, "lesungen=${t.lesungen}")
        assertTrue(t.proben <= 60, "proben=${t.proben}")
    }

    @Test
    fun nurZeilenNachDemCursor() = runTest {
        val zeilen = minuten(start, 3 * 1440)
        val cursor = zeilen[777]
        val t = Tabelle(zeilen)
        t.lauf(nach = cursor)
        assertEquals(zeilen.drop(778), t.bloecke.flatten())
    }

    @Test
    fun abgewiesenerBlockBeendetDenLaufUndKommtBeimNaechstenWieder() = runTest {
        val zeilen = minuten(start, 3 * 1440)
        val erster = Tabelle(zeilen)
        erster.lauf(nach = 0, scheitertAb = 1)
        assertEquals(1, erster.bloecke.size)
        // Der Aufrufer rueckt den Cursor nur bei Erfolg vor: Fortsetzung ab dem letzten Erfolg.
        val zweiter = Tabelle(zeilen)
        zweiter.lauf(nach = erster.bloecke.last().last())
        assertEquals(zeilen, erster.bloecke.flatten() + zweiter.bloecke.flatten())
    }

    @Test
    fun nichtsNachDemCursorLiestNichts() = runTest {
        val zeilen = minuten(start, 100)
        val t = Tabelle(zeilen)
        t.lauf(nach = zeilen.last(), letzte = zeilen.last())
        assertEquals(emptyList(), t.bloecke)
        assertEquals(0, t.lesungen)
    }

    @Test
    fun halbierungFindetDenErstenTagMitZeilen() = runTest {
        val erste = start + 123_456
        val gefunden = ersterTagMitZeilen(0, erste + 10 * BLOCK_SEKUNDEN) { von, bis -> erste in von until bis || erste + 5 in von until bis }
        val fenster = checkNotNull(gefunden)
        assertTrue(erste in fenster until fenster + BLOCK_SEKUNDEN)
        assertNull(ersterTagMitZeilen(0, erste) { _, _ -> false })
    }
}
