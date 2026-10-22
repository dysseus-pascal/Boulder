package boulder.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.escapeHTML
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Die Anmeldung fuer Claudes Connectors: OAuth 2.1, wie Claude es verlangt - Metadaten nach
 * RFC 9728 und 8414, Registrierung nach RFC 7591, Code mit PKCE (S256), rotierende
 * Erneuerungstokens. Feste Header in einem Connector sind bei Claude Beta und nicht jedem Konto
 * freigegeben; OAuth geht ueberall.
 *
 * EIN NUTZER, KEIN KONTO: wer das MCP-Token kennt, gibt Claude im Browser frei. Claude haelt danach
 * eigene Tokens, die nur /mcp oeffnen.
 *
 * GESPEICHERT WIRD NUR EIN PRUEFWERT, ein HMAC mit dem MCP-Token als Schluessel: die Datenbank
 * verraet keinen Zugang, und ein neues MCP-Token meldet jeden Client ab.
 *
 * WER NICHT ANGEMELDET IST, SCHREIBT NICHTS UND SPERRT NUR SICH: die Kennung eines Clients traegt
 * seine Daten selbst, unterschrieben ([kennung]), und die Sperre nach falschen Tokens gilt je
 * Absender ([gesperrt]). Sonst verdraengte eine Flut von Registrierungen den Client, den Claude
 * gerade angelegt hat, und zehn falsche Tokens eines Fremden sperrten den Besitzer aus.
 */
class Anmeldung(
    private val adresse: String,
    private val mcpToken: String,
    private val speicher: Speicher,
    private val jetzt: () -> Long,
) {
    private val zufall = SecureRandom()

    // Nur im Speicher: ein Code lebt eine Minute, ein Neustart dazwischen kostet eine neue Anmeldung.
    private val codes = ConcurrentHashMap<String, Code>()

    // Die Zeiten der falschen Tokens je Absender ([bereich]), der am laengsten ruhige zuerst; ab
    // HOECHSTENS_ABSENDER faellt er heraus. Nur unter synchronized(fehlversuche).
    private val fehlversuche = object : LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(aeltester: MutableMap.MutableEntry<String, ArrayDeque<Long>>) = size > HOECHSTENS_ABSENDER
    }

    // Ein eigener Schluessel fuer die Kennungen der Clients, aus dem MCP-Token: eine Unterschrift,
    // die /register jedem gibt, taugt so nirgends als Pruefwert eines Tokens.
    private val clientSchluessel = hmac(mcpToken.toByteArray(), "boulder-client".toByteArray())

    private class Code(val client: String, val umleitung: String, val challenge: String, val ablauf: Long)

    /** Eine gepruefte Anfrage an /authorize. */
    private class Anfrage(val client: Speicher.Client, val umleitung: String, val challenge: String, val state: String?)

    /** Fuer die Antwort 401 an /mcp: mit diesem Verweis beginnt Claude die Anmeldung. */
    fun herausforderung(tokenUngueltig: Boolean): String =
        "Bearer resource_metadata=\"$adresse/.well-known/oauth-protected-resource/mcp\"" +
            if (tokenUngueltig) ", error=\"invalid_token\"" else ""

    fun gilt(token: String): Boolean = speicher.zugangGilt(pruefwert(token), ZUGANG, jetzt())

    suspend fun ressource(call: ApplicationCall) = call.respondText(buildJsonObject {
        put("resource", "$adresse/mcp")
        putJsonArray("authorization_servers") { add(adresse) }
        putJsonArray("bearer_methods_supported") { add("header") }
        put("resource_name", "Boulder")
    }.toString(), ContentType.Application.Json)

    suspend fun server(call: ApplicationCall) = call.respondText(buildJsonObject {
        put("issuer", adresse)
        put("authorization_endpoint", "$adresse/authorize")
        put("token_endpoint", "$adresse/token")
        put("registration_endpoint", "$adresse/register")
        putJsonArray("response_types_supported") { add("code") }
        putJsonArray("grant_types_supported") { add("authorization_code"); add("refresh_token") }
        putJsonArray("code_challenge_methods_supported") { add("S256") }
        putJsonArray("token_endpoint_auth_methods_supported") { add("none") }
    }.toString(), ContentType.Application.Json)

    suspend fun registriere(call: ApplicationCall) {
        val r = koerper(call)?.let { runCatching { JSON.decodeFromString(Registrierung.serializer(), it) }.getOrNull() }
            ?: return jsonFehler(call, "invalid_client_metadata")
        if (r.umleitungen.isEmpty() || r.umleitungen.size > 10 || !r.umleitungen.all { it.length <= 200 && umleitungErlaubt(it) }) {
            return jsonFehler(call, "invalid_redirect_uri")
        }
        val daten = ClientDaten(r.umleitungen, r.name?.trim()?.take(60)?.ifEmpty { null } ?: "MCP client", neu().take(16))
        val c = Speicher.Client(kennung(daten), daten.name, daten.umleitungen)
        call.respondText(buildJsonObject {
            put("client_id", c.kennung)
            put("client_id_issued_at", jetzt())
            put("client_name", c.name)
            putJsonArray("redirect_uris") { c.umleitungen.forEach { add(it) } }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
        }.toString(), ContentType.Application.Json, HttpStatusCode.Created)
    }

    suspend fun frage(call: ApplicationCall) {
        val p = formularWerte(call.request.queryString())
            ?: return seite(call, HttpStatusCode.BadRequest, "<p>Malformed request.</p>")
        val a = pruefe(call, p) ?: return
        formularSeite(call, a, HttpStatusCode.OK, null)
    }

    suspend fun entscheide(call: ApplicationCall) {
        val p = formular(call) ?: return call.respondText("", status = HttpStatusCode.PayloadTooLarge)
        val a = pruefe(call, p) ?: return
        if (p["entscheid"] != "ja") return call.respondRedirect(ziel(a, "error" to "access_denied"))
        val t = jetzt()
        val wer = bereich(absender(call))
        // Vor dem Vergleich: auch das richtige Token hilft waehrend der Sperre nicht, sonst
        // verriete die Antwort dem Ratenden den Treffer doch.
        if (gesperrt(wer, t)) return formularSeite(call, a, HttpStatusCode.TooManyRequests, "Too many wrong tokens. Try again in 15 minutes.")
        if (!gleich(p["token"].orEmpty().trim(), mcpToken)) {
            synchronized(fehlversuche) { fehlversuche.getOrPut(wer) { ArrayDeque() }.addLast(t) }
            return formularSeite(call, a, HttpStatusCode.Forbidden, "Wrong token.")
        }
        val code = neu()
        codes.entries.removeIf { it.value.ablauf < t }
        codes[code] = Code(a.client.kennung, a.umleitung, a.challenge, t + CODE_S)
        call.respondRedirect(ziel(a, "code" to code))
    }

    suspend fun tausche(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val p = formular(call) ?: return jsonFehler(call, "invalid_request")
        val t = jetzt()
        val client = when (p["grant_type"]) {
            "authorization_code" -> {
                // remove: ein Code gilt einmal, auch wenn der Tausch scheitert.
                val code = p["code"]?.let { codes.remove(it) }
                val verifier = p["code_verifier"]
                if (code == null || code.ablauf < t || code.client != p["client_id"] ||
                    (p["redirect_uri"] != null && p["redirect_uri"] != code.umleitung) ||
                    verifier == null || !VERIFIER.matches(verifier) || !gleich(s256(verifier), code.challenge)
                ) return jsonFehler(call, "invalid_grant")
                code.client
            }
            "refresh_token" -> {
                val alt = p["refresh_token"] ?: return jsonFehler(call, "invalid_request")
                val besitzer = speicher.nimmZugang(pruefwert(alt), ERNEUERUNG, t)
                if (besitzer == null || (p["client_id"] != null && p["client_id"] != besitzer)) return jsonFehler(call, "invalid_grant")
                besitzer
            }
            else -> return jsonFehler(call, "unsupported_grant_type")
        }
        val zugang = neu()
        val erneuerung = neu()
        speicher.speichereZugang(pruefwert(zugang), ZUGANG, client, t + ZUGANG_S, t)
        speicher.speichereZugang(pruefwert(erneuerung), ERNEUERUNG, client, t + ERNEUERUNG_S, t)
        call.respondText(buildJsonObject {
            put("access_token", zugang)
            put("token_type", "Bearer")
            put("expires_in", ZUGANG_S)
            put("refresh_token", erneuerung)
        }.toString(), ContentType.Application.Json)
    }

    /**
     * Prueft eine Anfrage an /authorize; scheitert sie, ist die Antwort schon gegeben. Bis die
     * Umleitung feststeht, gibt es nur eine Fehlerseite: eine fremde Adresse bekaeme sonst den Fehler
     * und mit ihm einen Weg, Nutzer weiterzuleiten.
     */
    private suspend fun pruefe(call: ApplicationCall, p: Map<String, String>): Anfrage? {
        val client = p["client_id"]?.let(::client)
        if (client == null) {
            seite(call, HttpStatusCode.BadRequest, "<p>Unknown client. Remove the connector in Claude and add it again.</p>")
            return null
        }
        val verlangt = p["redirect_uri"]
        val umleitung = if (verlangt == null) client.umleitungen.singleOrNull()
        else verlangt.takeIf { v -> client.umleitungen.any { passt(it, v) } }
        if (umleitung == null) {
            seite(call, HttpStatusCode.BadRequest, "<p>This return address is not registered for the client.</p>")
            return null
        }
        val a = Anfrage(client, umleitung, p["code_challenge"].orEmpty(), p["state"])
        if (p["response_type"] != "code") {
            call.respondRedirect(ziel(a, "error" to "unsupported_response_type"))
            return null
        }
        if (!CHALLENGE.matches(a.challenge) || p["code_challenge_method"] != "S256") {
            call.respondRedirect(ziel(a, "error" to "invalid_request", "error_description" to "PKCE with S256 is required"))
            return null
        }
        return a
    }

    /** Hat [wer] in den letzten [SPERRE_S] Sekunden [HOECHSTENS_FEHLVERSUCHE] falsche Tokens geschickt? */
    private fun gesperrt(wer: String, t: Long): Boolean = synchronized(fehlversuche) {
        val l = fehlversuche[wer] ?: return false
        while (l.isNotEmpty() && l.first() < t - SPERRE_S) l.removeFirst()
        if (l.isEmpty()) fehlversuche.remove(wer)
        l.size >= HOECHSTENS_FEHLVERSUCHE
    }

    /**
     * Die Kennung eines Clients: seine Daten und ihr HMAC, beides Base64. Registrieren schreibt so
     * nichts, und ein neues MCP-Token entwertet jede Kennung wie jeden Zugang.
     */
    private fun kennung(d: ClientDaten): String {
        val inhalt = JSON.encodeToString(ClientDaten.serializer(), d).toByteArray()
        return B64.encodeToString(inhalt) + "." + B64.encodeToString(hmac(clientSchluessel, inhalt))
    }

    /**
     * Der Client hinter [kennung]; null, wenn die Unterschrift nicht stimmt. Eine Kennung ohne Punkt
     * stammt aus 0.55.0, als Clients noch in der Datenbank standen.
     */
    private fun client(kennung: String): Speicher.Client? {
        val teile = kennung.split('.')
        if (teile.size == 1) return speicher.client(kennung)
        if (teile.size != 2) return null
        val inhalt = runCatching { B64_LESEN.decode(teile[0]) }.getOrNull() ?: return null
        val unterschrift = runCatching { B64_LESEN.decode(teile[1]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(unterschrift, hmac(clientSchluessel, inhalt))) return null
        val d = runCatching { JSON.decodeFromString(ClientDaten.serializer(), inhalt.decodeToString()) }.getOrNull() ?: return null
        return Speicher.Client(kennung, d.name, d.umleitungen)
    }

    private suspend fun formularSeite(call: ApplicationCall, a: Anfrage, status: HttpStatusCode, fehler: String?) {
        val felder = listOfNotNull(
            "client_id" to a.client.kennung, "redirect_uri" to a.umleitung, "response_type" to "code",
            "code_challenge" to a.challenge, "code_challenge_method" to "S256", a.state?.let { "state" to it },
        )
        val zurueck = runCatching { URI(a.umleitung).host }.getOrNull() ?: a.umleitung
        seite(call, status, buildString {
            append("<p><b>${html(a.client.name)}</b> wants to read your Boulder health data. Access is read-only.</p>")
            append("<p>Afterwards you return to <b>${html(zurueck)}</b>.</p>")
            append("<form method=\"post\" action=\"/authorize\">")
            for ((k, v) in felder) append("<input type=\"hidden\" name=\"$k\" value=\"${html(v)}\">")
            append("<label for=\"token\">MCP token of your Boulder server</label>")
            append("<input id=\"token\" name=\"token\" type=\"password\" autocomplete=\"current-password\" autofocus>")
            if (fehler != null) append("<p class=\"fehler\">${html(fehler)}</p>")
            append("<button name=\"entscheid\" value=\"ja\" class=\"ja\">Allow</button>")
            append("<button name=\"entscheid\" value=\"nein\">Deny</button></form>")
        })
    }

    private fun ziel(a: Anfrage, vararg paare: Pair<String, String>): String {
        val alle = paare.toList() + listOfNotNull(a.state?.let { "state" to it })
        val trenner = if ('?' in a.umleitung) "&" else "?"
        // %20 statt "+": nicht jeder Client liest "+" in der Adresse als Leerzeichen, und der
        // state muss Zeichen fuer Zeichen zurueckkommen. Ein echtes "+" kodiert URLEncoder als %2B.
        return a.umleitung + trenner + alle.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20") }
    }

    private fun pruefwert(token: String): String = HexFormat.of().formatHex(hmac(mcpToken.toByteArray(), token.toByteArray()))

    private fun neu(): String = ByteArray(32).also(zufall::nextBytes).let(B64::encodeToString)

    companion object {
        /** Die Wege der Anmeldung; sie brauchen kein Token, sie vergeben eines. */
        val WEGE = setOf(
            "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp",
            "/.well-known/oauth-authorization-server", "/register", "/authorize", "/token",
        )
        const val CODE_S = 60L
        const val ZUGANG_S = 3600L

        /** Fragt Claude so lange nicht, muss man den Connector neu verbinden. */
        const val ERNEUERUNG_S = 90 * 86_400L
        const val SPERRE_S = 15 * 60L
        const val HOECHSTENS_FEHLVERSUCHE = 10

        /** So viele Absender merkt sich die Sperre; je Absender hoechstens zehn Zeiten. */
        const val HOECHSTENS_ABSENDER = 10_000
        private const val HOECHSTENS_FORMULAR = 16 * 1024
        private const val ZUGANG = "zugang"
        private const val ERNEUERUNG = "erneuerung"
        private val CHALLENGE = Regex("[A-Za-z0-9_-]{43}")
        private val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64_LESEN = Base64.getUrlDecoder()
        private val JSON = Json { ignoreUnknownKeys = true }
        private val CLAUDE = setOf("https://claude.ai/api/mcp/auth_callback", "https://claude.com/api/mcp/auth_callback")
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]")

        /**
         * Wohin ein Code gehen darf: zu Claude oder auf den eigenen Rechner (Claude Code). Jede
         * andere Adresse bekaeme ihn, sobald man auf einem untergeschobenen Link das Token eintippt.
         */
        internal fun umleitungErlaubt(uri: String): Boolean {
            if (uri in CLAUDE) return true
            val u = runCatching { URI(uri) }.getOrNull() ?: return false
            return u.scheme == "http" && u.host in LOOPBACK && u.rawUserInfo == null && u.rawFragment == null
        }

        /** RFC 8252: ein Programm auf dem eigenen Rechner lauscht bei jedem Start auf einem anderen Port. */
        internal fun passt(registriert: String, verlangt: String): Boolean {
            if (registriert == verlangt) return true
            val r = runCatching { URI(registriert) }.getOrNull() ?: return false
            val v = runCatching { URI(verlangt) }.getOrNull() ?: return false
            return r.scheme == "http" && r.host in LOOPBACK && v.scheme == r.scheme && v.host == r.host &&
                v.rawPath == r.rawPath && v.rawQuery == r.rawQuery && v.rawUserInfo == null && v.rawFragment == null
        }

        internal fun s256(verifier: String): String =
            B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun gleich(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun hmac(schluessel: ByteArray, daten: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(schluessel, "HmacSHA256")) }.doFinal(daten)

        private fun html(text: String) = text.escapeHTML()

        /**
         * Der Teil einer Adresse, den die Sperre zaehlt: IPv4 ganz, IPv6 je /64 - so viele Adressen
         * hat schon ein einzelner Anschluss, und jede bekaeme sonst eigene zehn Versuche.
         */
        internal fun bereich(absender: String): String {
            val b = Netz.literal(absender) ?: return absender
            return if (b.size == 16) HexFormat.of().formatHex(b, 0, 8) + "/64" else absender
        }

        /** Ein Formular oder eine Registrierung im Koerper; null, wenn er groesser ist, als die je sein muessen. */
        private suspend fun koerper(call: ApplicationCall): String? {
            val bytes = call.receiveChannel().readRemaining(HOECHSTENS_FORMULAR + 1L).readByteArray()
            return if (bytes.size > HOECHSTENS_FORMULAR) null else bytes.decodeToString()
        }

        private suspend fun formular(call: ApplicationCall): Map<String, String>? = koerper(call)?.let(::formularWerte)

        /**
         * application/x-www-form-urlencoded wie in RFC 6749, Anhang B: "+" ist ein Leerzeichen.
         * Null bei kaputter Kodierung oder doppelten Namen, die OAuth verbietet.
         */
        internal fun formularWerte(text: String): Map<String, String>? = runCatching {
            val paare = text.split('&').filter { it.isNotEmpty() }.map {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
            }
            paare.toMap().takeIf { it.size == paare.size }
        }.getOrNull()

        private suspend fun jsonFehler(call: ApplicationCall, fehler: String) = call.respondText(
            buildJsonObject { put("error", fehler) }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest,
        )

        private suspend fun seite(call: ApplicationCall, status: HttpStatusCode, inhalt: String) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "no-referrer")
            call.respondText(
                "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Boulder</title><style>" +
                    ":root{color-scheme:light dark}" +
                    "body{font-family:system-ui,sans-serif;margin:0;padding:24px 16px;background:#f6f6f4;color:#1d1d1b}" +
                    "main{max-width:420px;margin:0 auto}label{display:block;margin:16px 0 6px}" +
                    "input[type=password]{width:100%;box-sizing:border-box;padding:10px;font-size:16px;" +
                    "border:1px solid #8a8a85;border-radius:6px;background:transparent;color:inherit}" +
                    "button{margin:16px 8px 0 0;padding:10px 18px;font-size:16px;border-radius:6px;" +
                    "border:1px solid currentColor;background:transparent;color:inherit}" +
                    ".ja{background:#1d1d1b;border-color:#1d1d1b;color:#f6f6f4}.fehler{color:#b3261e}" +
                    "@media(prefers-color-scheme:dark){body{background:#1d1d1b;color:#ecece8}" +
                    ".ja{background:#ecece8;border-color:#ecece8;color:#1d1d1b}.fehler{color:#f2b8b5}}" +
                    "</style></head><body><main><h1>Boulder</h1>$inhalt</main></body></html>",
                ContentType.Text.Html.withCharset(Charsets.UTF_8), status,
            )
        }
    }
}

@Serializable
private class Registrierung(
    @SerialName("redirect_uris") val umleitungen: List<String> = emptyList(),
    @SerialName("client_name") val name: String? = null,
)

/**
 * Was die Kennung eines Clients traegt; kurze Namen, die Kennung steht in jeder Adresse an
 * /authorize. [zufall] macht jede Registrierung zu einem eigenen Client (RFC 7591): zwei Connectors
 * mit gleichen Angaben teilen sich sonst Kennung und Erneuerungstokens.
 */
@Serializable
private class ClientDaten(
    @SerialName("u") val umleitungen: List<String>,
    @SerialName("n") val name: String,
    @SerialName("z") val zufall: String,
)

internal fun Route.anmeldung(a: Anmeldung) {
    get("/.well-known/oauth-protected-resource") { a.ressource(call) }
    get("/.well-known/oauth-protected-resource/mcp") { a.ressource(call) }
    get("/.well-known/oauth-authorization-server") { a.server(call) }
    post("/register") { a.registriere(call) }
    get("/authorize") { a.frage(call) }
    post("/authorize") { a.entscheide(call) }
    post("/token") { a.tausche(call) }
}
