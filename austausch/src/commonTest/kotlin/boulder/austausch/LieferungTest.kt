package boulder.austausch

import kotlin.test.Test
import kotlin.test.assertEquals

class LieferungTest {
    private val voll = Lieferung(
        gesendet = 1_792_152_000L, zone = "Europe/Zurich", app = "0.55.0",
        tage = listOf(Tag("2026-10-16", mapOf("schritte" to 8123.0, "schlaf" to 433.0), entfernt = listOf("wasser"))),
        naechte = listOf(Nacht(
            beginn = 1_791_914_400L, utcZuLokal = 7200, einschlafen = 97, aufwachen = 586, schlafMinuten = 433,
            ruhepuls = 51, hrv = 54, messstufe = 4, abschnitte = listOf(Abschnitt(97, 130, 1), Abschnitt(130, 160, 2)),
            hrvFenster = listOf(HrvFenster(120, 61, 52)), geladen = true,
        )),
        trainings = listOf(Training("s:1", "Bike", 1_792_153_200L, 1_792_155_900L, 2_700L, 14_200.0, 128, 145)),
        minuten = listOf(Minute(1_792_152_000L, 3, 812, 104, 55)),
        stand = Stand("v4.41.1-b31", mapOf("schritte" to 10_000), 190),
        trainingsEntfernt = listOf("s:0"),
    )

    @Test
    fun rundlauf() {
        val text = austauschJson.encodeToString(Lieferung.serializer(), voll)
        assertEquals(voll, austauschJson.decodeFromString(Lieferung.serializer(), text))
    }

    @Test
    fun neuereGegenseiteMitUnbekanntenFeldern() {
        // So schickt eine spaetere Fassung: ein Feld mehr oben und in einem Tag. Es darf nichts ablehnen.
        val text = """{"fassung":2,"gesendet":1,"zone":"UTC","app":"9.9","neu":true,"tage":[{"datum":"2026-10-16","werte":{},"x":1}]}"""
        val l = austauschJson.decodeFromString(Lieferung.serializer(), text)
        assertEquals(2, l.fassung)
        assertEquals(listOf(Tag("2026-10-16", emptyMap())), l.tage)
    }

    @Test
    fun aeltereGegenseiteOhneListen() {
        val l = austauschJson.decodeFromString(Lieferung.serializer(), """{"gesendet":1,"zone":"UTC","app":"0.55.0"}""")
        assertEquals(Lieferung.FASSUNG, l.fassung)
        assertEquals(emptyList(), l.minuten)
        assertEquals(null, l.stand)
        assertEquals(emptyList(), l.trainingsEntfernt)
    }

    @Test
    fun quittungEinesServers055() {
        val q = austauschJson.decodeFromString(Quittung.serializer(), """{"tage":1,"naechte":2,"trainings":3,"minuten":4}""")
        assertEquals(Quittung(1, 2, 3, 4), q)
        assertEquals(null, q.trainingsEntfernt)
    }

    @Test
    fun tagUndNachtVonBoulder055() {
        // So schickte 0.55.0: ohne entfernt und ohne geladen.
        val text = """{"gesendet":1,"zone":"UTC","app":"0.55.0","tage":[{"datum":"2026-10-16","werte":{"wasser":1500.0}}],""" +
            """"naechte":[{"beginn":1,"utcZuLokal":0,"einschlafen":1,"aufwachen":2,"schlafMinuten":1,"ruhepuls":0,"hrv":0}]}"""
        val l = austauschJson.decodeFromString(Lieferung.serializer(), text)
        assertEquals(Tag("2026-10-16", mapOf("wasser" to 1500.0)), l.tage.single())
        assertEquals(false, l.naechte.single().geladen)
    }
}
