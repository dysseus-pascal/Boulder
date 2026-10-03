package coredevices.pebble.kiesel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HerzgrenzenTest {
    @Test
    fun nicht_gesetzt_heisst_tanaka() {
        // Alter 40: 208 - 28 = 180
        assertEquals(Herzgrenzen.Maximum(180, nachAlter = true), Herzgrenzen.maximum(null, 40, 55))
    }

    @Test
    fun gemessener_wert_gilt_wenn_plausibel() {
        assertEquals(Herzgrenzen.Maximum(188, nachAlter = false), Herzgrenzen.maximum(188, 40, 55))
        // Ein gemessener Wert von 190 ist jetzt einstellbar - frueher hiess 190 "nicht gesetzt".
        assertEquals(190, Herzgrenzen.maxpuls(190, 40, 55))
        // Ueber Tanaka + 25 ist ein Tippfehler
        assertEquals(Herzgrenzen.Maximum(180, nachAlter = true), Herzgrenzen.maximum(220, 40, 55))
        // Kaum ueber dem Ruhepuls ebenso
        assertEquals(180, Herzgrenzen.maxpuls(70, 40, 55))
    }

    @Test
    fun ohne_alter_bleibt_die_einstellung_oder_die_vorgabe() {
        assertEquals(KieselEinstellungen.MAXPULS_VORGABE, Herzgrenzen.maxpuls(null, null, 55))
        assertEquals(200, Herzgrenzen.maxpuls(200, null, 55))
    }

    @Test
    fun gemeldete_190_ohne_wahl_sind_nicht_gesetzt() {
        // So stand es bis 0.42 gespeichert: die Vorgabe der Uhr, nie eingestellt.
        assertNull(Herzgrenzen.eingestellt(KieselEinstellungen.MAXPULS_VORGABE, null, null))
        assertEquals(185, Herzgrenzen.eingestellt(185, null, null))
    }

    @Test
    fun die_wahl_gilt_fuer_den_wert_mit_dem_sie_zur_uhr_ging() {
        // Gewaehlt 190, bestaetigt: gesetzt.
        assertEquals(190, Herzgrenzen.eingestellt(190, true, 190))
        // Nach Alter, als 179 zur Uhr geschickt: nicht gesetzt.
        assertNull(Herzgrenzen.eingestellt(179, false, 179))
        // Danach auf der Konfigseite der Uhr 185 eingestellt: die Wahl gilt nicht mehr.
        assertEquals(185, Herzgrenzen.eingestellt(185, false, 179))
    }

    @Test
    fun eine_verspaetet_bestaetigte_wahl_gilt_fuer_ihren_wert() {
        // "From age" 179 geschickt, die Meldung kam nach dem Warten: die Schaetzung bleibt eine.
        assertNull(Herzgrenzen.eingestellt(179, null, null, wartend = false, wartendWert = 179))
        // Gemessene 190 geschickt, spaet bestaetigt: gesetzt, nicht die alte Vorgabe.
        assertEquals(190, Herzgrenzen.eingestellt(190, false, 179, wartend = true, wartendWert = 190))
        // Die Uhr hat nichts uebernommen: fuer ihren alten Wert gilt die alte Wahl weiter.
        assertEquals(185, Herzgrenzen.eingestellt(185, true, 185, wartend = false, wartendWert = 179))
    }

    @Test
    fun ruhepuls_ist_der_median_der_letzten_naechte() {
        // Eine Fiebernacht mit 70 verschiebt den Median nicht
        assertEquals(53, Herzgrenzen.ruhepuls(listOf(52, 53, 54, 0, 53, 70)))
    }

    @Test
    fun tanaka_wie_die_uhr() {
        // Die Uhr rechnet in Zehnteln (puls.c prv_tanaka): dieselbe Zahl fuer jedes Alter.
        for (alter in 10..100) assertEquals((2080 - 7 * alter + 5) / 10, Herzgrenzen.tanaka(alter), "Alter $alter")
    }

    @Test
    fun meldet_die_uhr_nach_alter_zaehlt_ihre_zahl_nicht() {
        // Die Uhr rechnet mit ihrem Alter (187), das Telefon mit seinem (179): beide "nicht gesetzt".
        val uhr = KieselEinstellungen.Stand(maxpuls = 187, maxpulsGesetzt = false, uhrRechnetNachAlter = true)
        val entwurf = uhr.copy(maxpuls = 179)
        assertTrue(entwurf.gleich(uhr))
        // Gesetzt zaehlt die Zahl, und gesetzt gegen nach Alter ist verschieden.
        assertFalse(uhr.copy(maxpulsGesetzt = true).gleich(entwurf.copy(maxpulsGesetzt = true)))
        assertFalse(uhr.gleich(uhr.copy(maxpulsGesetzt = true)))
        // Aeltere Firmware rechnet mit der Zahl des Telefons: dort zaehlt sie auch ohne Einstellung.
        val alt = uhr.copy(uhrRechnetNachAlter = false)
        assertFalse(alt.copy(maxpuls = 179).gleich(alt))
    }
}
