package boulder.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnmeldungTest {
    private val sync = "s".repeat(40)
    private val mcp = "m".repeat(40)
    private val adresse = "https://boulder.example"
    private val claude = "https://claude.ai/api/mcp/auth_callback"
    private val verifier = "v".repeat(20) + "-._~" + "w".repeat(30)
    private var uhr = 1_792_152_000L

    private fun ApplicationTestBuilder.server(
        speicher: Speicher = Speicher.oeffne(":memory:"),
        token: String = mcp,
        mitAdresse: Boolean = true,
    ) {
        application { boulder(Einstellungen(8080, ":memory:", sync, token, emptyList(), 90, adresse.takeIf { mitAdresse }), speicher) { uhr } }
    }

    /** Wie ein Browser, aber Umleitungen sieht der Test selbst. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { followRedirects = false }

    private suspend fun json(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    private suspend fun HttpClient.registriere(vararg umleitungen: String = arrayOf(claude)): HttpResponse = post("/register") {
        contentType(ContentType.Application.Json)
        setBody(
            """{"client_name":"Claude","redirect_uris":[${umleitungen.joinToString(",") { "\"$it\"" }}],""" +
                """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],"token_endpoint_auth_method":"none"}"""
        )
    }

    private suspend fun HttpClient.kennung(): String {
        val r = registriere()
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return json(r)["client_id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.gibFrei(
        client: String,
        token: String = mcp,
        umleitung: String = claude,
        state: String = "a b+c",
        entscheid: String = "ja",
        von: String? = null,
    ): HttpResponse = submitForm("/authorize", parameters {
        append("response_type", "code")
        append("client_id", client)
        append("redirect_uri", umleitung)
        append("code_challenge", Anmeldung.s256(verifier))
        append("code_challenge_method", "S256")
        append("state", state)
        append("token", token)
        append("entscheid", entscheid)
    }) {
        // So meldet cloudflared im selben Pod die Adresse im Internet (absender in App.kt).
        if (von != null) header("CF-Connecting-IP", von)
    }

    /** Der Code aus der Umleitung zu Claude. */
    private fun code(r: HttpResponse): String {
        assertEquals(HttpStatusCode.Found, r.status)
        val ort = Url(r.headers[HttpHeaders.Location]!!)
        assertEquals("a b+c", ort.parameters["state"])
        return ort.parameters["code"]!!
    }

    private suspend fun HttpClient.tausche(client: String, code: String, v: String = verifier) = submitForm("/token", parameters {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", claude)
        append("client_id", client)
        append("code_verifier", v)
    })

    private suspend fun HttpClient.erneuere(client: String, erneuerung: String) = submitForm("/token", parameters {
        append("grant_type", "refresh_token")
        append("refresh_token", erneuerung)
        append("client_id", client)
    })

    /** Zugangs- und Erneuerungstoken einer ganzen Anmeldung. */
    private suspend fun HttpClient.melde(): Triple<String, String, String> {
        val client = kennung()
        val antwort = tausche(client, code(gibFrei(client)))
        assertEquals(HttpStatusCode.OK, antwort.status, antwort.bodyAsText())
        val j = json(antwort)
        return Triple(client, j["access_token"]!!.jsonPrimitive.content, j["refresh_token"]!!.jsonPrimitive.content)
    }

    private suspend fun HttpClient.mcpMit(token: String) = post("/mcp") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody("{}")
    }

    private suspend fun fehler(r: HttpResponse): String = json(r)["error"]!!.jsonPrimitive.content

    @Test
    fun ohneAdresseKeineAnmeldung() = testApplication {
        server(mitAdresse = false)
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/oauth-authorization-server").status)
        assertEquals(HttpStatusCode.NotFound, client.registriere().status)
        val r = client.post("/mcp") { setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("Bearer", r.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun metadatenWieClaudeSieSucht() = testApplication {
        server()
        val r = client.post("/mcp") { setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(
            "Bearer resource_metadata=\"$adresse/.well-known/oauth-protected-resource/mcp\"",
            r.headers[HttpHeaders.WWWAuthenticate],
        )
        val ressource = json(client.get("/.well-known/oauth-protected-resource/mcp"))
        assertEquals("$adresse/mcp", ressource["resource"]!!.jsonPrimitive.content)
        assertEquals(listOf(adresse), ressource["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content })
        val server = json(client.get("/.well-known/oauth-authorization-server"))
        assertEquals(adresse, server["issuer"]!!.jsonPrimitive.content)
        assertEquals("$adresse/authorize", server["authorization_endpoint"]!!.jsonPrimitive.content)
        assertEquals("$adresse/token", server["token_endpoint"]!!.jsonPrimitive.content)
        assertEquals("$adresse/register", server["registration_endpoint"]!!.jsonPrimitive.content)
        assertEquals(listOf("S256"), server["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Die Sync-Seite bleibt beim eigenen Token und verweist nicht auf die Anmeldung.
        assertEquals("Bearer", client.post("/sync") { setBody("{}") }.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun registrierungNurMitClaudesRueckweg() = testApplication {
        server()
        for (fremd in listOf("https://evil.example/cb", "http://localhost@evil.example/callback", "http://localhost.evil.example/callback", "javascript:alert(1)")) {
            val r = client.registriere(fremd)
            assertEquals(HttpStatusCode.BadRequest, r.status, fremd)
            assertEquals("invalid_redirect_uri", fehler(r))
        }
        assertEquals(HttpStatusCode.BadRequest, client.registriere(claude, "https://evil.example/cb").status)
        assertEquals(HttpStatusCode.Created, client.registriere(claude).status)
        assertEquals(HttpStatusCode.Created, client.registriere("http://127.0.0.1:3118/callback").status)
        val kaputt = client.post("/register") { setBody("kein json") }
        assertEquals("invalid_client_metadata", fehler(kaputt))
    }

    @Test
    fun claudeMeldetSichAnUndLiest() = testApplication {
        server()
        val b = browser()
        val client = b.kennung()
        val formular = b.get("/authorize") {
            parameter("response_type", "code")
            parameter("client_id", client)
            parameter("redirect_uri", claude)
            parameter("code_challenge", Anmeldung.s256(verifier))
            parameter("code_challenge_method", "S256")
            parameter("state", "a b+c")
        }
        assertEquals(HttpStatusCode.OK, formular.status)
        assertTrue("claude.ai" in formular.bodyAsText())
        assertEquals("DENY", formular.headers["X-Frame-Options"])

        val antwort = b.tausche(client, code(b.gibFrei(client)))
        assertEquals("no-store", antwort.headers[HttpHeaders.CacheControl])
        val j = json(antwort)
        assertEquals("Bearer", j["token_type"]!!.jsonPrimitive.content)
        assertEquals(Anmeldung.ZUGANG_S.toString(), j["expires_in"]!!.jsonPrimitive.content)
        val zugang = j["access_token"]!!.jsonPrimitive.content

        val http = createClient {
            install(SSE)
            install(ContentNegotiation) { json() }
            defaultRequest { header(HttpHeaders.Authorization, "Bearer $zugang") }
        }
        val c = Client(Implementation("test", "1"))
        c.connect(StreamableHttpClientTransport(http, "/mcp"))
        assertEquals(6, c.listTools().tools.size)
        c.close()

        // Claudes Zugang liest nur: schreiben darf allein Boulder mit dem Sync-Token.
        assertEquals(HttpStatusCode.Unauthorized, b.post("/sync") { bearerAuth(zugang); setBody("{}") }.status)
    }

    @Test
    fun erneuernTauschtDieTokens() = testApplication {
        server()
        val b = browser()
        val (client, zugang, erneuerung) = b.melde()
        // Ein Erneuerungstoken oeffnet /mcp nicht; es lebt neunzig Tage statt einer Stunde.
        assertEquals(HttpStatusCode.Unauthorized, b.mcpMit(erneuerung).status)
        val neu = b.erneuere(client, erneuerung)
        assertEquals(HttpStatusCode.OK, neu.status)
        val j = json(neu)
        assertNotEquals(erneuerung, j["refresh_token"]!!.jsonPrimitive.content)
        assertNotEquals(HttpStatusCode.Unauthorized, b.mcpMit(j["access_token"]!!.jsonPrimitive.content).status)
        assertNotEquals(HttpStatusCode.Unauthorized, b.mcpMit(zugang).status)
        // Das alte Erneuerungstoken ist eingeloest.
        assertEquals("invalid_grant", fehler(b.erneuere(client, erneuerung)))
        // Fremde Clients loesen kein Erneuerungstoken ein.
        val (_, _, zweite) = b.melde()
        assertEquals("invalid_grant", fehler(b.erneuere(client, zweite)))
    }

    @Test
    fun einCodeGiltEinmalKurzUndNurMitDemVerifier() = testApplication {
        server()
        val b = browser()
        val client = b.kennung()
        val code = code(b.gibFrei(client))
        assertEquals("invalid_grant", fehler(b.tausche(client, code, v = "x".repeat(50))))
        // Auch ein gescheiterter Tausch verbraucht den Code.
        assertEquals("invalid_grant", fehler(b.tausche(client, code)))

        val zweiter = code(b.gibFrei(client))
        assertEquals("invalid_grant", fehler(b.tausche(b.kennung(), zweiter)))

        val dritter = code(b.gibFrei(client))
        uhr += Anmeldung.CODE_S + 1
        assertEquals("invalid_grant", fehler(b.tausche(client, dritter)))

        val vierter = code(b.gibFrei(client))
        assertEquals(HttpStatusCode.OK, b.tausche(client, vierter).status)
        assertEquals("invalid_grant", fehler(b.tausche(client, vierter)))
        assertEquals("unsupported_grant_type", fehler(b.submitForm("/token", parameters { append("grant_type", "client_credentials") })))
    }

    @Test
    fun fremdeRueckwegeBekommenNichts() = testApplication {
        server()
        val b = browser()
        val client = b.kennung()
        val fremd = b.gibFrei(client, umleitung = "https://evil.example/cb")
        assertEquals(HttpStatusCode.BadRequest, fremd.status)
        assertNull(fremd.headers[HttpHeaders.Location])
        val unbekannt = b.gibFrei("unbekannt")
        assertEquals(HttpStatusCode.BadRequest, unbekannt.status)
        assertNull(unbekannt.headers[HttpHeaders.Location])

        val ohnePkce = b.get("/authorize") {
            parameter("response_type", "code")
            parameter("client_id", client)
            parameter("redirect_uri", claude)
        }
        val ort = Url(ohnePkce.headers[HttpHeaders.Location]!!)
        assertEquals("invalid_request", ort.parameters["error"])
        assertNull(ort.parameters["code"])

        val abgelehnt = Url(b.gibFrei(client, entscheid = "nein").headers[HttpHeaders.Location]!!)
        assertEquals("access_denied", abgelehnt.parameters["error"])
        assertNull(abgelehnt.parameters["code"])
    }

    @Test
    fun falscheTokensSperrenDieAnmeldung() = testApplication {
        server()
        val b = browser()
        val client = b.kennung()
        repeat(Anmeldung.HOECHSTENS_FEHLVERSUCHE) {
            val r = b.gibFrei(client, token = "falsch".repeat(8))
            assertEquals(HttpStatusCode.Forbidden, r.status)
            assertNull(r.headers[HttpHeaders.Location])
        }
        val gesperrt = b.gibFrei(client)
        assertEquals(HttpStatusCode.TooManyRequests, gesperrt.status)
        assertNull(gesperrt.headers[HttpHeaders.Location])
        uhr += Anmeldung.SPERRE_S + 1
        code(b.gibFrei(client))
    }

    @Test
    fun einFremderSperrtNurSichSelbst() = testApplication {
        server()
        val b = browser()
        val client = b.kennung()
        repeat(Anmeldung.HOECHSTENS_FEHLVERSUCHE) {
            assertEquals(HttpStatusCode.Forbidden, b.gibFrei(client, token = "falsch".repeat(8), von = "203.0.113.7").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, b.gibFrei(client, von = "203.0.113.7").status)
        // Der Besitzer an einem anderen Anschluss meldet sich trotzdem an.
        code(b.gibFrei(client, von = "198.51.100.2"))
        // IPv6 zaehlt je /64: eine andere Adresse im selben Netz bleibt gesperrt, ein anderes Netz nicht.
        repeat(Anmeldung.HOECHSTENS_FEHLVERSUCHE) { b.gibFrei(client, token = "falsch".repeat(8), von = "2001:db8:1:2::1") }
        assertEquals(HttpStatusCode.TooManyRequests, b.gibFrei(client, von = "2001:db8:1:2:ffff::9").status)
        code(b.gibFrei(client, von = "2001:db8:1:3::1"))
    }

    @Test
    fun bereicheDerSperre() {
        assertEquals("203.0.113.7", Anmeldung.bereich("203.0.113.7"))
        assertEquals("20010db800010002/64", Anmeldung.bereich("2001:db8:1:2::1"))
        assertEquals(Anmeldung.bereich("2001:db8:1:2::1"), Anmeldung.bereich("[2001:db8:1:2:ffff::9]"))
        assertEquals("localhost", Anmeldung.bereich("localhost"))
    }

    @Test
    fun registrierenSchreibtNichtsUndVerdraengtNichts() {
        val speicher = Speicher.oeffne(":memory:")
        lateinit var claude: String
        testApplication {
            server(speicher)
            val b = browser()
            claude = b.kennung()
            // Eine Flut von Registrierungen, waehrend der Besitzer das Token eintippt.
            repeat(300) { assertEquals(HttpStatusCode.Created, b.registriere("http://localhost:$it/callback").status) }
            code(b.gibFrei(claude))
        }
        assertEquals(null, speicher.client(claude))
        // Die Kennung gilt auch nach einem Neustart, ohne Datenbank - mit demselben MCP-Token.
        testApplication {
            server(Speicher.oeffne(":memory:"))
            code(browser().gibFrei(claude))
        }
    }

    @Test
    fun eineVeraenderteKennungGiltNicht() = testApplication {
        server()
        val b = browser()
        val echt = b.kennung()
        val (daten, unterschrift) = echt.split('.')
        val fremd = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"u":["https://evil.example/cb"],"n":"Claude","z":"x"}""".toByteArray())
        for (falsch in listOf("$fremd.$unterschrift", "$daten.${unterschrift.reversed()}", "$daten.$unterschrift.x", "$daten.!!")) {
            val r = b.gibFrei(falsch, umleitung = "https://evil.example/cb")
            assertEquals(HttpStatusCode.BadRequest, r.status, falsch)
            assertNull(r.headers[HttpHeaders.Location])
        }
    }

    @Test
    fun zugaengeLaufenAb() = testApplication {
        server()
        val b = browser()
        val (client, zugang, erneuerung) = b.melde()
        uhr += Anmeldung.ZUGANG_S + 1
        val abgelaufen = b.mcpMit(zugang)
        assertEquals(HttpStatusCode.Unauthorized, abgelaufen.status)
        assertTrue("error=\"invalid_token\"" in abgelaufen.headers[HttpHeaders.WWWAuthenticate]!!)
        val neu = json(b.erneuere(client, erneuerung))
        uhr += Anmeldung.ERNEUERUNG_S + 1
        assertEquals("invalid_grant", fehler(b.erneuere(client, neu["refresh_token"]!!.jsonPrimitive.content)))
    }

    @Test
    fun einNeuesMcpTokenMeldetAlleAb() {
        val speicher = Speicher.oeffne(":memory:")
        lateinit var alt: Triple<String, String, String>
        testApplication {
            server(speicher)
            alt = browser().melde()
        }
        testApplication {
            server(speicher, token = "n".repeat(40))
            val b = browser()
            assertEquals(HttpStatusCode.Unauthorized, b.mcpMit(alt.second).status)
            assertEquals("invalid_grant", fehler(b.erneuere(alt.first, alt.third)))
            // Auch die Kennung des Clients gilt nicht mehr: Claude registriert sich neu.
            assertEquals(HttpStatusCode.BadRequest, b.gibFrei(alt.first, token = "n".repeat(40)).status)
        }
    }

    @Test
    fun dasFormularMaskiertEingaben() = testApplication {
        server()
        val b = browser()
        val seite = b.get("/authorize") {
            parameter("response_type", "code")
            parameter("client_id", b.kennung())
            parameter("redirect_uri", claude)
            parameter("code_challenge", Anmeldung.s256(verifier))
            parameter("code_challenge_method", "S256")
            parameter("state", "\"><script>alert(1)</script>")
        }.bodyAsText()
        assertFalse("<script>" in seite)
        assertTrue("&quot;&gt;&lt;script&gt;" in seite)
    }

    @Test
    fun zuGrosseKoerperWerdenNichtGelesen() = testApplication {
        server()
        val gross = "x=" + "y".repeat(20_000)
        assertEquals("invalid_request", fehler(client.post("/token") { setBody(gross) }))
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/authorize") { setBody(gross) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/register") { setBody(gross) }.status)
    }

    @Test
    fun rueckwegeAufDemEigenenRechner() {
        assertTrue(Anmeldung.umleitungErlaubt(claude))
        assertTrue(Anmeldung.umleitungErlaubt("http://localhost:3118/callback"))
        assertTrue(Anmeldung.umleitungErlaubt("http://[::1]:3118/callback"))
        assertFalse(Anmeldung.umleitungErlaubt("https://claude.ai/api/mcp/auth_callback/../evil"))
        assertFalse(Anmeldung.umleitungErlaubt("http://127.0.0.1:3118@evil.example/callback"))
        assertFalse(Anmeldung.umleitungErlaubt("http://localhost:3118/callback#x"))
        // Ein anderer Port ja, ein anderer Pfad oder Rechner nicht.
        assertTrue(Anmeldung.passt("http://127.0.0.1:3118/callback", "http://127.0.0.1:4000/callback"))
        assertFalse(Anmeldung.passt("http://127.0.0.1:3118/callback", "http://127.0.0.1:4000/anders"))
        assertFalse(Anmeldung.passt("http://127.0.0.1:3118/callback", "http://localhost:4000/callback"))
        assertFalse(Anmeldung.passt(claude, "https://claude.ai/api/mcp/auth_callback2"))
    }

    @Test
    fun formulareWieOAuthSieSchickt() = testApplication {
        assertEquals(mapOf("state" to "a b+c", "leer" to ""), Anmeldung.formularWerte("state=a+b%2Bc&leer="))
        assertNull(Anmeldung.formularWerte("state=1&state=2"))
        assertNull(Anmeldung.formularWerte("state=%zz"))
        server()
        val b = browser()
        val doppelt = b.get("/authorize?client_id=${b.kennung()}&client_id=x&response_type=code")
        assertEquals(HttpStatusCode.BadRequest, doppelt.status)
        assertNull(doppelt.headers[HttpHeaders.Location])
    }
}
