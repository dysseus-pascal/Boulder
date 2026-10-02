package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DrinktervallTest {
    private val heute = LocalDate.of(2026, 10, 2)

    @Test
    fun das_erhoehte_ziel_gilt_nur_am_tag_der_meldung() {
        // Gestern auf 10 Glaeser erhoeht, das Soll ist 8: heute zaehlt das Soll, bis die Uhr sich meldet.
        assertEquals(Drinktervall.Ziel(8, 300), Drinktervall.zielFuer(heute, heute.minusDays(1), 10, 8, 300))
        assertEquals(Drinktervall.Ziel(10, 300), Drinktervall.zielFuer(heute, heute, 10, 8, 300))
        assertEquals(2400, Drinktervall.zielFuer(heute, heute.minusDays(1), 10, 8, 300).ml)
    }

    @Test
    fun ohne_gemerktes_soll_bleibt_das_letzte_ziel() {
        assertEquals(Drinktervall.Ziel(10, 250), Drinktervall.zielFuer(heute, heute.minusDays(3), 10, null, 250))
        assertEquals(Drinktervall.Ziel(10, 250), Drinktervall.zielFuer(heute, null, 10, null, 250))
    }

    @Test
    fun nur_glas_oder_kaffee_muss_das_bestaetigen_ueberleben() {
        assertEquals(true, Drinktervall.traegtEintrag(KieselNachricht(mapOf(Drinktervall.DT_DRANK_AT to 1L))))
        assertEquals(true, Drinktervall.traegtEintrag(KieselNachricht(mapOf(Drinktervall.DT_COFFEE_AT to 1L))))
        assertEquals(false, Drinktervall.traegtEintrag(KieselNachricht(mapOf(Drinktervall.DT_GLASSES to 8L))))
    }

    private val beides = KieselNachricht(
        mapOf(
            Drinktervall.DT_GLASSES to 8L, Drinktervall.DT_GLASS_ML to 300L, Drinktervall.DT_DRANK_AT to 1_790_000_100L,
            Drinktervall.DT_COFFEE_AT to 1_790_000_000L, Drinktervall.DT_COFFEE_KIND to 4L,
            Drinktervall.DT_DRINK_KCAL to 25L, Drinktervall.DT_DRINK_MG to 80L,
        ),
        mapOf(Drinktervall.DT_DRINK_NAME to "Mate"),
    )

    @Test
    fun liegen_bleibt_nur_der_gescheiterte_teil() {
        // Kaffee eingetragen, Glas gescheitert: der Kaffee kaeme sonst bei jedem Start wieder.
        val nurGlas = Drinktervall.rest(beides, kaffeeFehlt = false, glasFehlt = true)
        assertEquals(setOf(Drinktervall.DT_GLASSES, Drinktervall.DT_GLASS_ML, Drinktervall.DT_DRANK_AT), nurGlas.zahlen.keys)
        assertEquals(emptyMap(), nurGlas.texte)
        assertEquals(true, Drinktervall.traegtEintrag(nurGlas))
        // Glas eingetragen, Kaffee gescheitert: das Glas faellt heraus, der Kaffee bleibt ganz.
        val nurKaffee = Drinktervall.rest(beides, kaffeeFehlt = true, glasFehlt = false)
        assertEquals(false, Drinktervall.DT_DRANK_AT in nurKaffee.zahlen)
        assertEquals(80L, nurKaffee.zahlen[Drinktervall.DT_DRINK_MG])
        assertEquals("Mate", nurKaffee.texte[Drinktervall.DT_DRINK_NAME])
        // Beides gescheitert: alles bleibt.
        assertEquals(beides.zahlen, Drinktervall.rest(beides, true, true).zahlen)
    }
}
