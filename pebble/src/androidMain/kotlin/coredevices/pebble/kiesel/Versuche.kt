package coredevices.pebble.kiesel

import java.time.LocalDate
import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException

/** Ein Tag aus dem eigenen Speicher ("2026-10-03"), oder null mit einer Zeile im Log unter [wo]. */
internal fun tagOderNull(text: String?, wo: String): LocalDate? = text?.let { versuche(wo, "Kein Tag: $it") { LocalDate.parse(it) } }

/**
 * Ein Zugriff, dessen Fehler die Seite nicht kippen soll: gelingt er nicht,
 * steht null da und eine Warnung mit [tag] und [was] im Log. Statt
 * runCatching - das schluckte jeden Fehler still, und eine leere Seite liess
 * sich spaeter nicht erklaeren.
 *
 * DER ABBRUCH GEHT DURCH: verlaesst man die Seite, waehrend sie laedt, endet
 * das Laden gleich, statt jede weitere Abfrage noch anzustossen.
 */
internal inline fun <T> versuche(tag: String, was: String, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.withTag(tag).w { "$was: " + e.message }
    null
}
