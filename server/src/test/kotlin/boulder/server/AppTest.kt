package boulder.server

import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.Tag
import boulder.austausch.austauschJson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppTest {
    private val sync = "s".repeat(40)
    private val mcp = "m".repeat(40)
    private val jetzt = 1_792_152_000L
    private val lieferung = """{"gesendet":1,"zone":"Europe/Zurich","app":"0.55.0","tage":[{"datum":"2026-10-16","werte":{"schritte":8123.0}}]}"""

    private fun ApplicationTestBuilder.server(netze: List<Netz> = emptyList(), speicher: Speicher = Speicher.oeffne(":memory:")) {
        application { boulder(Einstellungen(8080, ":memory:", sync, mcp, netze, 90), speicher) { jetzt } }
    }

    @Test
    fun gesundOhneToken() = testApplication {
        server()
        assertEquals(HttpStatusCode.OK, client.get("/gesund").status)
    }

    @Test
    fun unbekannteWegeGibtEsNicht() = testApplication {
        server()
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/admin").status)
    }

    @Test
    fun syncNurMitDemSyncToken() = testApplication {
        server()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/sync") { setBody(lieferung) }.status)
        // Das Token, das bei Claude liegt, darf nicht schreiben.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/sync") { bearerAuth(mcp); setBody(lieferung) }.status)
        val ok = client.post("/sync") {
            bearerAuth(sync)
            contentType(ContentType.Application.Json)
            setBody(lieferung)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(Quittung(1, 0, 0, 0), austauschJson.decodeFromString(Quittung.serializer(), ok.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, client.post("/sync") { bearerAuth(sync); setBody("kein json") }.status)
    }

    @Test
    fun mcpNurMitDemMcpToken() = testApplication {
        server()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { bearerAuth(sync); setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { header(HttpHeaders.Authorization, mcp); setBody("{}") }.status)
    }

    @Test
    fun mcpNurAusErlaubtenNetzen() = testApplication {
        server(netze = listOf(Netz.aus("160.79.104.0/21")))
        assertEquals(HttpStatusCode.Forbidden, client.post("/mcp") {
            bearerAuth(mcp)
            header("CF-Connecting-IP", "203.0.113.9")
            setBody("{}")
        }.status)
        // Aus dem erlaubten Netz geht es zum MCP-Teil; der lehnt den leeren Aufruf ab, aber nicht als 401 oder 403.
        val durch = client.post("/mcp") {
            bearerAuth(mcp)
            header("CF-Connecting-IP", "160.79.105.1")
            setBody("{}")
        }.status
        assertTrue(durch != HttpStatusCode.Forbidden && durch != HttpStatusCode.Unauthorized, "$durch")
    }

    @Test
    fun claudeLiestUeberMcp() = testApplication {
        val speicher = Speicher.oeffne(":memory:")
        speicher.uebernimm(
            Lieferung(gesendet = jetzt, zone = "Europe/Zurich", app = "0.55.0", tage = listOf(Tag("2026-10-16", mapOf("schritte" to 8123.0)))),
            jetzt, 90,
        )
        server(speicher = speicher)
        val http = createClient {
            install(SSE)
            install(ContentNegotiation) { json() }
            defaultRequest { header(HttpHeaders.Authorization, "Bearer $mcp") }
        }
        val c = Client(Implementation("test", "1"))
        c.connect(StreamableHttpClientTransport(http, "/mcp"))
        assertEquals(setOf("stand", "tage", "nacht", "trainings", "puls", "minuten"), c.listTools().tools.map { it.name }.toSet())
        val tage = c.callTool("tage", mapOf("von" to "2026-10-16", "bis" to "2026-10-16"))
        val text = (tage.content.single() as TextContent).text
        assertTrue("2026-10-16,8123" in text, text)
        val falsch = c.callTool("tage", mapOf("von" to "16.10.2026", "bis" to "2026-10-16"))
        assertEquals(true, falsch.isError)
        c.close()
    }
}
