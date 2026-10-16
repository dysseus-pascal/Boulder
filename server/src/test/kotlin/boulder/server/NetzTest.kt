package boulder.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetzTest {
    @Test
    fun anthropicsBereich() {
        val n = Netz.aus("160.79.104.0/21")
        assertTrue(n.enthaelt("160.79.104.0"))
        assertTrue(n.enthaelt("160.79.111.255"))
        assertFalse(n.enthaelt("160.79.112.0"))
        assertFalse(n.enthaelt("160.79.103.255"))
    }

    @Test
    fun ipv6() {
        val n = Netz.aus("2607:6bc0::/48")
        assertTrue(n.enthaelt("2607:6bc0:0:ffff::1"))
        assertFalse(n.enthaelt("2607:6bc1::1"))
        assertFalse(n.enthaelt("160.79.104.1"))
    }

    @Test
    fun namenWerdenNichtAufgeloest() {
        assertFalse(Netz.aus("127.0.0.0/8").enthaelt("localhost"))
        assertFailsWith<IllegalArgumentException> { Netz.aus("example.com/24") }
        assertFailsWith<IllegalArgumentException> { Netz.aus("10.0.0.0/33") }
    }

    @Test
    fun absenderHinterDemTunnel() {
        // cloudflared im Pod ruft von localhost; nur dann zaehlt der Kopf von Cloudflare.
        assertEquals("160.79.105.1", absender("127.0.0.1", "160.79.105.1"))
        assertEquals("160.79.105.1", absender("localhost", "160.79.105.1"))
        assertEquals("203.0.113.9", absender("203.0.113.9", "160.79.105.1"))
        assertEquals("127.0.0.1", absender("127.0.0.1", null))
    }
}
