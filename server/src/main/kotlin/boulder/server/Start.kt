package boulder.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val e = Einstellungen.aus(System.getenv())
    val speicher = Speicher.oeffne(e.datenbank)
    // 0.0.0.0 IM CONTAINER: erreichbar ist er nur im Pod, fuer cloudflared - veroeffentlicht wird
    // kein Port (LIESMICH.md).
    embeddedServer(CIO, port = e.port, host = "0.0.0.0") { boulder(e, speicher) }.start(wait = true)
}
