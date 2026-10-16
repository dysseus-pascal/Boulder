package boulder.server

import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.austauschJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.security.MessageDigest

/** Groesser wird eine Lieferung nicht: drei Tage Minuten sind gut 300 KB. */
const val HOECHSTENS_BYTES = 20L * 1024 * 1024

fun Application.boulder(e: Einstellungen, speicher: Speicher, jetzt: () -> Long = { System.currentTimeMillis() / 1000 }) {
    // ERST PRUEFEN, DANN ROUTEN: ein Aufruf ohne das passende Token erreicht weder den Speicher
    // noch den MCP-Teil. Jeder Weg ausser /gesund braucht eines.
    intercept(ApplicationCallPipeline.Plugins) {
        val pfad = call.request.path()
        val fehler = when {
            pfad == "/gesund" -> null
            pfad == "/sync" -> if (traegt(call, e.syncToken)) null else HttpStatusCode.Unauthorized
            pfad == "/mcp" || pfad.startsWith("/mcp/") -> when {
                e.mcpNetze.isNotEmpty() && e.mcpNetze.none { it.enthaelt(absender(call)) } -> HttpStatusCode.Forbidden
                !traegt(call, e.mcpToken) -> HttpStatusCode.Unauthorized
                else -> null
            }
            else -> HttpStatusCode.NotFound
        }
        if (fehler != null) {
            call.application.log.info("Abgewiesen: $pfad ($fehler)")
            call.respondText("", status = fehler)
            finish()
        }
    }
    routing {
        get("/gesund") { call.respondText("ok") }
        post("/sync") {
            val bytes = call.receiveChannel().readRemaining(HOECHSTENS_BYTES + 1).readByteArray()
            if (bytes.size > HOECHSTENS_BYTES) {
                call.respondText("zu gross", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val lieferung = runCatching { austauschJson.decodeFromString(Lieferung.serializer(), bytes.decodeToString()) }
                .getOrElse {
                    call.respondText("unlesbar: ${it.message}", status = HttpStatusCode.BadRequest)
                    return@post
                }
            val q = speicher.uebernimm(lieferung, jetzt(), e.minutenTage)
            call.application.log.info("Lieferung ${lieferung.app}: ${q.tage} Tage, ${q.naechte} Naechte, ${q.trainings} Trainings, ${q.minuten} Minuten")
            call.respondText(austauschJson.encodeToString(Quittung.serializer(), q), ContentType.Application.Json)
        }
    }
    // ZUSTANDSLOS: jede Anfrage bekommt einen frischen MCP-Server. Die Werkzeuge lesen nur, eine
    // Sitzung haette nichts zu merken - und nach einem Neustart des Containers keine, die fehlt.
    // OHNE DNS-REBINDING-SCHUTZ: der prueft den Host-Kopf gegen localhost, hier steht aber der Name
    // des Tunnels. Er schuetzt offene lokale Server vor Webseiten im Browser; diesen schuetzt das
    // Token, das keine fremde Webseite kennt.
    mcpStatelessStreamableHttp(path = "/mcp", enableDnsRebindingProtection = false) { mcpServer(speicher) }
}

/** Traegt der Aufruf "Authorization: Bearer [token]"? Der Vergleich dauert gleich lang, wo er auch abweicht. */
internal fun traegt(call: ApplicationCall, token: String): Boolean {
    val kopf = call.request.headers[HttpHeaders.Authorization] ?: return false
    if (!kopf.startsWith("Bearer ")) return false
    return MessageDigest.isEqual(kopf.removePrefix("Bearer ").trim().toByteArray(), token.toByteArray())
}

/**
 * Wer fragt. Hinter dem Cloudflare Tunnel ruft cloudflared aus demselben Pod an, also von
 * localhost; die Adresse im Internet steht dann in CF-Connecting-IP. DEM KOPF NUR VON LOCALHOST
 * GLAUBEN: kaeme ein Aufruf an cloudflared vorbei, setzte er ihn selbst.
 */
internal fun absender(call: ApplicationCall): String =
    absender(call.request.origin.remoteAddress, call.request.headers["CF-Connecting-IP"])

internal fun absender(direkt: String, cloudflare: String?): String {
    val lokal = direkt == "localhost" || Netz.literal(direkt)?.let { InetAddress.getByAddress(it).isLoopbackAddress } == true
    return if (lokal && cloudflare != null) cloudflare else direkt
}
