import androidx.compose.runtime.Composable

/**
 * Boulder: zeigt nichts. Cores Knopf fuehrte zum Bug-Report an Core Devices; Boulder schickt
 * nichts ueber seine App oder Firmware dorthin. Die Signatur bleibt, damit die Aufrufer im
 * Index-Teil (experimental) unveraendert bleiben.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun BugReportButton(
    coreNav: CoreNav,
    pebble: Boolean,
    screenContext: Map<String, String> = emptyMap(),
    recordingPath: String? = null,
) {
}

class NextBugReportContext {
    var nextContext: String? = null
}
