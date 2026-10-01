package coredevices.pebble.kiesel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KaffeesortenTest {
    @Test
    fun espressoMitZucker() {
        val p = Kaffeesorten.portion(Kaffeesorten.ESPRESSO or 0x20)!!
        assertEquals(63.0, p.koffeinMg)
        assertEquals(18.0, p.kcal)
        assertEquals(4.0, p.zuckerG)
    }

    @Test
    fun kaffeeMitMilchUndZucker() {
        val p = Kaffeesorten.portion(Kaffeesorten.KAFFEE or 0x30)!!
        assertEquals(true, p.milch)
        assertEquals(2.0 + 19.0 + 16.0, p.kcal)
    }

    @Test
    fun milchZaehltNurBeimKaffee() {
        // Ein alter Milch-Haken beim Espresso darf keine kcal bringen.
        val p = Kaffeesorten.portion(Kaffeesorten.ESPRESSO or 0x10)!!
        assertEquals(false, p.milch)
        assertEquals(2.0, p.kcal)
    }

    @Test
    fun energyDrinkMitUndOhneZucker() {
        assertEquals(112.0, Kaffeesorten.portion(Kaffeesorten.ENERGY or 0x20)!!.kcal)
        assertEquals(8.0, Kaffeesorten.portion(Kaffeesorten.ENERGY)!!.kcal)
        assertEquals(80.0, Kaffeesorten.portion(Kaffeesorten.ENERGY)!!.koffeinMg)
    }

    @Test
    fun unbekannteSorte() {
        assertNull(Kaffeesorten.portion(7))
    }
}

class EigenesGetraenkTest {
    @Test
    fun werteVonDerUhrBegrenzt() {
        val p = Kaffeesorten.eigen(180, 0)
        assertEquals(180.0, p.kcal)
        assertEquals(0.0, p.koffeinMg)
        assertEquals(2000.0, Kaffeesorten.eigen(99999, 5).kcal)
    }
}
