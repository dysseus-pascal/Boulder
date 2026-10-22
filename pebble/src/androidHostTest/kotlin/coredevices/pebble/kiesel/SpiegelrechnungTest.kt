package coredevices.pebble.kiesel

import boulder.austausch.HrvFenster
import boulder.austausch.Tag
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpiegelrechnungTest {
    private val jetzt = 1_792_152_000L

    @Test
    fun nurHttpsUndOhnePfad() {
        assertEquals("https://boulder.example.ch", Spiegelrechnung.adresse(" https://boulder.example.ch/ "))
        assertEquals("https://boulder.example.ch:8443", Spiegelrechnung.adresse("https://boulder.example.ch:8443"))
        for (falsch in listOf(
            "http://boulder.example.ch", "boulder.example.ch", "https://boulder.example.ch/mcp", "https://a@boulder.example.ch",
            "https://boulder.example.ch?x=1", "https://boulder.example.ch#a", "https://", "", "https://bou lder.ch",
        )) assertNull(Spiegelrechnung.adresse(falsch), falsch)
    }

    @Test
    fun tokenWieDerServerEsVerlangt() {
        assertEquals("x".repeat(64), Spiegelrechnung.token("  " + "x".repeat(64) + "\n"))
        assertNull(Spiegelrechnung.token("x".repeat(31)))
        assertNull(Spiegelrechnung.token("x".repeat(20) + " " + "y".repeat(20)))
    }

    @Test
    fun nurNeueOderGeaenderteTage() {
        val tage = listOf(
            Tag("2026-10-15", mapOf("schritte" to 9000.0, "schlaf" to 431.0)),
            Tag("2026-10-16", mapOf("schritte" to 5000.0)),
        )
        val erste = Spiegelrechnung.neueTage(tage, emptyMap())
        assertEquals(listOf("2026-10-15", "2026-10-16"), erste.map { it.datum })
        val gesendet = erste.associate { it.datum to Spiegelrechnung.abdruck(it) }
        assertEquals(emptyList(), Spiegelrechnung.neueTage(tage, gesendet))
        // Die Reihenfolge der Spalten ist kein neuer Wert.
        val umgestellt = listOf(Tag("2026-10-15", linkedMapOf("schlaf" to 431.0, "schritte" to 9000.0)), tage[1])
        assertEquals(emptyList(), Spiegelrechnung.neueTage(umgestellt, gesendet))
        val spaeter = listOf(tage[0], Tag("2026-10-16", mapOf("schritte" to 7500.0)))
        assertEquals(listOf(Tag("2026-10-16", mapOf("schritte" to 7500.0))), Spiegelrechnung.neueTage(spaeter, gesendet))
        // Was ein Tag als entfernt nennt, gehoert zu seinem Abdruck.
        assertEquals(1, Spiegelrechnung.neueTage(listOf(tage[1].copy(entfernt = listOf("wasser"))), gesendet).size)
    }

    @Test
    fun einTagOhneEntferntBehaeltDenAbdruckVon055() {
        // So rechnete 0.55.0 den Abdruck der Werte: nach dem Update geht kein Tag deshalb noch einmal.
        assertEquals(Spiegelrechnung.abdruck("schlaf=431.0;schritte=9000.0"), Spiegelrechnung.abdruck(Tag("2026-10-15", mapOf("schritte" to 9000.0, "schlaf" to 431.0))))
    }

    @Test
    fun alteGesendeteNaechteLiestNiemandMehr() {
        fun nacht(beginn: Long) = NachtEntity(beginn, 7200, 100, 560, 430, 52, 48)
        val alt = nacht(jetzt - 10 * 86_400L)
        val frisch = nacht(jetzt - 86_400L)
        val nieGesendet = nacht(jetzt - 30 * 86_400L)
        val gesendet = mapOf(alt.beginnUtc.toString() to "a", frisch.beginnUtc.toString() to "b")
        assertEquals(listOf(frisch, nieGesendet), Spiegelrechnung.zuPruefen(listOf(alt, frisch, nieGesendet), gesendet, jetzt))
    }

    @Test
    fun eineNachtImAustauschformatGeordnet() {
        val e = NachtEntity(jetzt, 7200, 95, 560, 431, 52, 48, akkuBeginn = 80, akkuEnde = 74, messstufe = 3)
        val n = Spiegelrechnung.nacht(
            e,
            listOf(NachtAbschnittEntity(jetzt, 200, 260, 2), NachtAbschnittEntity(jetzt, 95, 200, 1)),
            listOf(NachtHrvEntity(jetzt, 150, 49, 54), NachtHrvEntity(jetzt, 120, 57, 0)),
        )
        assertEquals(listOf(95, 200), n.abschnitte.map { it.von })
        assertEquals(listOf(HrvFenster(120, 57, 0), HrvFenster(150, 49, 54)), n.hrvFenster)
        assertEquals(listOf(7200, 95, 560, 431, 52, 48, 3, 80, 74), listOf(n.utcZuLokal, n.einschlafen, n.aufwachen, n.schlafMinuten, n.ruhepuls, n.hrv, n.messstufe, n.akkuBeginn, n.akkuEnde))
        // Ein nachgereichtes HRV-Fenster aendert die Nacht.
        assertNotEquals(Spiegelrechnung.abdruck(n), Spiegelrechnung.abdruck(n.copy(hrvFenster = n.hrvFenster + HrvFenster(180, 44, 53))))
        assertEquals(Spiegelrechnung.abdruck(n), Spiegelrechnung.abdruck(n.copy()))
    }

    @Test
    fun minutenMitRueckblickUndNieVorDerAeltesten() {
        assertEquals(1_000L, Spiegelrechnung.minutenAb(null, 1_000L))
        assertEquals(10_000L - Spiegelrechnung.MINUTEN_RUECKBLICK_S, Spiegelrechnung.minutenAb(10_000L, 1_000L))
        assertEquals(1_000L, Spiegelrechnung.minutenAb(1_200L, 1_000L))
    }

    @Test
    fun reihenJeTag() {
        val tag = LocalDate.of(2026, 10, 15)
        val reihen = mapOf("schritte" to listOf(tag to 9000.0), "schlaf" to listOf(tag to 431.0, tag.plusDays(1) to 400.0))
        assertEquals(mapOf(tag to mapOf("schritte" to 9000.0, "schlaf" to 431.0), tag.plusDays(1) to mapOf("schlaf" to 400.0)), Spiegelrechnung.jeTag(reihen))
    }

    @Test
    fun tageNennenNurGanzGeleseneSpaltenAlsEntfernt() {
        val heute = LocalDate.of(2026, 10, 16)
        val eigen = mapOf(
            "schritte" to listOf(heute.minusDays(1) to 9000.0, heute.minusDays(200) to 8000.0),
            "wasser" to listOf(heute.minusDays(1) to 1500.0),
        )
        // Die Akte antwortete nicht ganz: die Tage gehen trotzdem und nennen nichts als entfernt.
        val ohneAkte = Spiegelrechnung.tage(Trendstand(eigen, emptyMap()), heute)
        assertEquals(listOf("2026-03-30", "2026-10-15"), ohneAkte.map { it.datum })
        assertTrue(ohneAkte.all { it.entfernt.isEmpty() })
        // Wasser ganz gelesen: im Fenster der Akte nennt jeder Tag ohne Wasser es als entfernt,
        // auch einer ohne jeden anderen Wert; der Tag vor dem Fenster nicht.
        val mitWasser = Spiegelrechnung.tage(Trendstand(eigen, emptyMap(), akteGanz = setOf("wasser")), heute)
        assertEquals(Tag("2026-10-15", mapOf("schritte" to 9000.0, "wasser" to 1500.0)), mitWasser.single { it.datum == "2026-10-15" })
        assertEquals(Tag("2026-10-16", emptyMap(), listOf("wasser")), mitWasser.single { it.datum == "2026-10-16" })
        assertEquals(Tag("2026-03-30", mapOf("schritte" to 8000.0)), mitWasser.single { it.datum == "2026-03-30" })
        assertEquals(Trenddaten.TAGE + 2, mitWasser.size.toLong())
        assertEquals(mitWasser.map { it.datum }.sorted(), mitWasser.map { it.datum })
    }
}
