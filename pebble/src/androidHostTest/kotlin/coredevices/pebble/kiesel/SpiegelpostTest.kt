package coredevices.pebble.kiesel

import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.Tag
import boulder.austausch.austauschJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class SpiegelpostTest {
    private val ziel = Spiegel.Ziel("https://boulder.example.ch", "t".repeat(40))
    private val lieferung = Lieferung(gesendet = 1, zone = "Europe/Zurich", app = "0.55.0", tage = listOf(Tag("2026-10-16", mapOf("schritte" to 8123.0))))

    private fun post(antwort: MockRequestHandler) = Spiegelpost(HttpClient(MockEngine(antwort)) { expectSuccess = false })

    @Test
    fun angenommenMitTokenUndLieferungAlsJson() = runBlocking {
        var anfrage: HttpRequestData? = null
        val p = post { r ->
            anfrage = r
            respond("""{"tage":1,"naechte":0,"trainings":0,"minuten":0}""", HttpStatusCode.OK)
        }
        assertEquals(Spiegelpost.Antwort.Angenommen(Quittung(1, 0, 0, 0)), p.schicke(ziel, lieferung))
        val r = anfrage!!
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://boulder.example.ch/sync", r.url.toString())
        assertEquals("Bearer ${ziel.token}", r.headers[HttpHeaders.Authorization])
        val koerper = r.body as TextContent
        assertEquals(ContentType.Application.Json, koerper.contentType.withoutParameters())
        assertEquals(lieferung, austauschJson.decodeFromString(Lieferung.serializer(), koerper.text))
    }

    @Test
    fun abgelehntMitDemStatus() = runBlocking {
        assertEquals(Spiegelpost.Antwort.Abgelehnt(401), post { respond("", HttpStatusCode.Unauthorized) }.schicke(ziel, lieferung))
        assertEquals(Spiegelpost.Antwort.Abgelehnt(502), post { respond("", HttpStatusCode.BadGateway) }.schicke(ziel, lieferung))
    }

    @Test
    fun eineSeiteStattQuittungIstKeinErfolg() = runBlocking {
        // Etwa eine Anmeldeseite vor dem Server: 200, aber keine Quittung.
        assertEquals(Spiegelpost.Antwort.Abgelehnt(0), post { respond("<html>Sign in</html>", HttpStatusCode.OK) }.schicke(ziel, lieferung))
    }

    @Test
    fun ohneNetzUnerreichbar() = runBlocking {
        assertEquals(Spiegelpost.Antwort.Unerreichbar("kein Netz"), post { throw IOException("kein Netz") }.schicke(ziel, lieferung))
    }
}
