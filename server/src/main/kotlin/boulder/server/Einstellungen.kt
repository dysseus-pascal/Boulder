package boulder.server

import java.io.File

/**
 * Die Einstellungen aus der Umgebung des Containers. Ein Token kommt als Wert
 * (BOULDER_SYNC_TOKEN) oder als Datei (BOULDER_SYNC_TOKEN_FILE, fuer Podman-Secrets).
 *
 * OHNE TOKENS STARTET DER SERVER NICHT: er steht im Internet, ungeschuetzt laese jeder die
 * Gesundheitsdaten. ZWEI VERSCHIEDENE TOKENS: das fuer MCP liegt bei Claude, also bei Anthropic;
 * damit darf niemand Daten schreiben, nur Boulder mit dem eigenen.
 */
data class Einstellungen(
    val port: Int,
    val datenbank: String,
    val syncToken: String,
    val mcpToken: String,
    /** Leer: jede Adresse. Sonst nur aus diesen Netzen an /mcp (Anthropic: 160.79.104.0/21). */
    val mcpNetze: List<Netz>,
    /** So lange bleiben Minuten; Tage, Naechte und Trainings bleiben ganz. */
    val minutenTage: Int,
    /** Die oeffentliche Adresse, etwa https://boulder.example.ch. Gesetzt: Claude meldet sich per OAuth an (Anmeldung.kt). */
    val adresse: String? = null,
) {
    companion object {
        const val MIN_TOKEN = 32
        private val ADRESSE = Regex("""https://[A-Za-z0-9.-]+(:[0-9]{1,5})?""")

        fun aus(umgebung: Map<String, String>, liesDatei: (String) -> String = { File(it).readText() }): Einstellungen {
            fun token(name: String): String {
                val t = (umgebung[name] ?: umgebung["${name}_FILE"]?.let(liesDatei)).orEmpty().trim()
                require(t.length >= MIN_TOKEN) { "$name fehlt oder hat weniger als $MIN_TOKEN Zeichen" }
                return t
            }
            val sync = token("BOULDER_SYNC_TOKEN")
            val mcp = token("BOULDER_MCP_TOKEN")
            require(sync != mcp) { "BOULDER_SYNC_TOKEN und BOULDER_MCP_TOKEN muessen verschieden sein" }
            return Einstellungen(
                port = umgebung["BOULDER_PORT"]?.toInt() ?: 8080,
                datenbank = umgebung["BOULDER_DATENBANK"] ?: "/daten/boulder.db",
                syncToken = sync,
                mcpToken = mcp,
                mcpNetze = umgebung["BOULDER_MCP_NETZE"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(Netz::aus),
                minutenTage = umgebung["BOULDER_MINUTEN_TAGE"]?.toInt() ?: 90,
                adresse = umgebung["BOULDER_ADRESSE"]?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }?.also {
                    // Ohne Pfad: die Wege der Anmeldung liegen an der Wurzel, und Claude prueft die
                    // Adressen in den Metadaten Zeichen fuer Zeichen.
                    require(ADRESSE.matches(it)) { "BOULDER_ADRESSE: https://name[:port] ohne Pfad, nicht $it" }
                },
            )
        }
    }
}
