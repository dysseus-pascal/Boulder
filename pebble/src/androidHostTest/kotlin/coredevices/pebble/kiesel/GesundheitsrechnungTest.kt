package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GesundheitsrechnungTest {
    private val beginn = 1_790_000_000L

    @Test
    fun phasen_werden_je_art_summiert() {
        val a = listOf(
            NachtAbschnittEntity(beginn, 60, 120, 1),
            NachtAbschnittEntity(beginn, 120, 150, 2),
            NachtAbschnittEntity(beginn, 150, 155, 0),
            NachtAbschnittEntity(beginn, 155, 200, 3),
            NachtAbschnittEntity(beginn, 200, 260, 1),
        )
        assertEquals(listOf(5, 120, 30, 45, 0), Gesundheitsrechnung.phasenMinuten(a).toList())
    }

    @Test
    fun nacht_in_zeitpunkten_und_ohne_messung_null() {
        val e = NachtEntity(beginn, 7200, einschlafen = 60, aufwachen = 540, schlafMinuten = 460, ruhepuls = 0, hrv = 42)
        val n = Gesundheitsrechnung.nacht(e, listOf(NachtAbschnittEntity(beginn, 60, 540, 4)))
        assertEquals(Instant.ofEpochSecond(beginn + 3600), n.einschlafen)
        assertEquals(Instant.ofEpochSecond(beginn + 540 * 60), n.aufwachen)
        assertNull(n.ruhepuls)
        assertEquals(42, n.hrv)
        assertTrue(!n.mitPhasen)
    }

    @Test
    fun koffein_halbiert_sich_in_fuenf_stunden() {
        val t = Instant.ofEpochSecond(beginn)
        val dosen = listOf(t to 80.0, t.plusSeconds(5 * 3600) to 40.0, t.plusSeconds(20 * 3600) to 100.0)
        assertEquals(80.0, Gesundheitsrechnung.koffeinAktiv(dosen, t.plusSeconds(5 * 3600)), 0.01)
    }

    @Test
    fun die_nacht_gehoert_zum_tag_des_aufwachens() {
        // Fensterbeginn 20:00 UTC am 28.09., aufgewacht 07:00 -> Nacht zum 29.09.
        val b = LocalDate.of(2026, 9, 28).atTime(20, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 9, 29), Gesundheitsrechnung.nachtTag(b, 11 * 60, ZoneOffset.UTC))
        // Ein Nickerchen, das noch vor Mitternacht endet, gehoert zum 28.09.
        assertEquals(LocalDate.of(2026, 9, 28), Gesundheitsrechnung.nachtTag(b, 3 * 60, ZoneOffset.UTC))
    }

    @Test
    fun je_tag_bleibt_die_laengste_nacht() {
        // Aeltere Firmware: zwei Naechte fuer denselben Morgen (22-01 und 01:40-06:30).
        val b = LocalDate.of(2026, 9, 28).atTime(20, 0).toEpochSecond(ZoneOffset.UTC)
        val erste = NachtEntity(b, 7200, 120, 300, 175, 52, 60)
        val zweite = NachtEntity(b + 300 * 60, 7200, 40, 330, 285, 50, 70)
        val andere = NachtEntity(b - 86400, 7200, 130, 660, 500, 51, 65)
        val je = Gesundheitsrechnung.jeTag(listOf(andere, erste, zweite), ZoneOffset.UTC)
        assertEquals(listOf(andere, zweite), je)
    }
}
