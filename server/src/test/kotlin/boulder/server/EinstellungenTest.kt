package boulder.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EinstellungenTest {
    private val sync = "s".repeat(32)
    private val mcp = "m".repeat(32)

    @Test
    fun ohneTokensStartetNichts() {
        assertFailsWith<IllegalArgumentException> { Einstellungen.aus(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Einstellungen.aus(mapOf("BOULDER_SYNC_TOKEN" to sync)) }
    }

    @Test
    fun kurzeOderGleicheTokensReichenNicht() {
        assertFailsWith<IllegalArgumentException> {
            Einstellungen.aus(mapOf("BOULDER_SYNC_TOKEN" to "kurz", "BOULDER_MCP_TOKEN" to mcp))
        }
        assertFailsWith<IllegalArgumentException> {
            Einstellungen.aus(mapOf("BOULDER_SYNC_TOKEN" to sync, "BOULDER_MCP_TOKEN" to sync))
        }
    }

    @Test
    fun tokenAusDateiWieBeiPodmanSecrets() {
        val e = Einstellungen.aus(
            mapOf("BOULDER_SYNC_TOKEN_FILE" to "/run/secrets/sync", "BOULDER_MCP_TOKEN" to mcp),
            liesDatei = { assertEquals("/run/secrets/sync", it); "$sync\n" },
        )
        assertEquals(sync, e.syncToken)
    }

    @Test
    fun vorgabenUndNetze() {
        val e = Einstellungen.aus(mapOf("BOULDER_SYNC_TOKEN" to sync, "BOULDER_MCP_TOKEN" to mcp))
        assertEquals(8080, e.port)
        assertEquals("/daten/boulder.db", e.datenbank)
        assertEquals(90, e.minutenTage)
        assertTrue(e.mcpNetze.isEmpty())
        val mitNetz = Einstellungen.aus(mapOf("BOULDER_SYNC_TOKEN" to sync, "BOULDER_MCP_TOKEN" to mcp, "BOULDER_MCP_NETZE" to "160.79.104.0/21, 2607:6bc0::/48"))
        assertEquals(listOf("160.79.104.0/21", "2607:6bc0:0:0:0:0:0:0/48"), mitNetz.mcpNetze.map { it.toString() })
    }
}
