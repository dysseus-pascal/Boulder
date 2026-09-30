package coredevices.pebble.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Laesst eine Arbeit nie zweimal gleichzeitig laufen. Wer anstoesst, waehrend
 * sie laeuft, kehrt sofort zurueck; der laufende Durchgang wiederholt sich dann
 * genau einmal, damit der spaete Anstoss nicht verloren geht.
 *
 * `hoechstens` begrenzt die Durchgaenge je Aufruf: wer eine befristete
 * Hintergrundfrist haelt (goAsync), soll sie nicht ueber endlose Nachlaeufe
 * strecken. Bleibt dabei ein Anstoss offen, meldet [lauf] das mit [Ausgang.OFFEN].
 */
class Einzellauf(
    private val hoechstens: Int = Int.MAX_VALUE,
    private val beiFehler: (Exception) -> Unit = {},
) {
    enum class Ausgang { GEMACHT, UEBERGEBEN, OFFEN }

    private val _laeuft = MutableStateFlow(false)
    val laeuft: StateFlow<Boolean> = _laeuft
    private val nochmal = MutableStateFlow(false)

    suspend fun lauf(arbeit: suspend () -> Unit): Ausgang {
        nochmal.value = true
        var durchgaenge = 0
        while (nochmal.value) {
            if (!_laeuft.compareAndSet(expect = false, update = true)) {
                return if (durchgaenge > 0) Ausgang.GEMACHT else Ausgang.UEBERGEBEN
            }
            try {
                // Anstoesse bis hierher deckt der naechste Durchgang ab.
                while (durchgaenge < hoechstens && nochmal.compareAndSet(expect = true, update = false)) {
                    durchgaenge++
                    // Ein Fehler darf die Anstoesse, die waehrenddessen kamen, nicht mitreissen.
                    try {
                        arbeit()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        beiFehler(e)
                    }
                }
            } finally {
                _laeuft.value = false
            }
            if (durchgaenge >= hoechstens) return if (nochmal.value) Ausgang.OFFEN else Ausgang.GEMACHT
            // Kam ein Anstoss zwischen der letzten Pruefung und dem Freigeben,
            // hat sein Aufrufer noch "laeuft" gesehen - also hier nachholen.
        }
        return Ausgang.GEMACHT
    }
}
