package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.BuiltInCompanions
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Der Begleiter von Kieselsport - in Boulder eingebaut.
 *
 * ER BESTAETIGT ZUERST, DANN ARBEITET ER. Die Uhr wartet auf die Bestaetigung,
 * bevor sie die naechste Nachricht schickt; das Eintragen in die Akte kann
 * Sekunden dauern. Bestaetigt wird jede Nachricht von Kieselsport, auch eine,
 * mit der hier nichts anzufangen ist - sonst schickte die Uhr sie endlos
 * wieder. Gibt es keine Akte, bleibt es trotzdem bei der Bestaetigung: die
 * Zusammenfassung liegt dann im Protokoll als nicht eingetragen.
 */
internal class KieselsportBegleiter(
    private val context: Context,
    private val device: CompanionAppDevice,
) : CompanionApp {
    private val sitzung = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun start(incomingAppMessages: Flow<AppMessageData>) {
        sitzung.launch {
            incomingAppMessages.collect { nachricht ->
                device.sendAppMessageResult(AppMessageResult.ACK(nachricht.transactionId))
                auftraege.send(Auftrag(context.applicationContext, device, nachricht))
            }
        }
    }

    override suspend fun stop() {
        // Nur der Empfang endet - was schon bestaetigt ist, wird fertig
        // verarbeitet (siehe unten).
        sitzung.cancel()
    }

    private class Auftrag(val context: Context, val device: CompanionAppDevice, val nachricht: AppMessageData)

    companion object {
        private val logger = Logger.withTag("Kieselsport")

        /**
         * DIE VERARBEITUNG UEBERLEBT DIE SITZUNG. Kieselsport geht auf der Uhr
         * zu, sobald das Telefon bestaetigt hat - und damit endet der
         * Empfang. Haengte das Eintragen an ihm, bräche es mitten in der Akte
         * ab. Also eine Warteschlange fuer die ganze App: bestaetigt wird
         * sofort, gearbeitet der Reihe nach, und die Reihenfolge
         * (Zusammenfassung vor Kurve) bleibt.
         */
        private val auftraege = Channel<Auftrag>(Channel.UNLIMITED)

        init {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for (a in auftraege) {
                    try {
                        Kieselsport.verarbeite(a.context, zerlege(a.nachricht.data)) { antwort ->
                            a.device.sendAppMessage(
                                AppMessageData(
                                    transactionId = a.device.transactionSequence.next(),
                                    uuid = Kieselsport.UUID,
                                    data = antwort,
                                )
                            )
                        }?.let { KieselVerlauf.merke(a.context, it) }
                    } catch (e: Exception) {
                        logger.e(e) { "Nachricht nicht verarbeitet: ${e.message}" }
                    }
                }
            }
        }

        /** Zahlen, Texte und Bytes getrennt - so, wie die Aufgaben sie lesen. */
        fun zerlege(daten: Map<Int, Any>): KieselNachricht {
            val zahlen = mutableMapOf<Int, Long>()
            val texte = mutableMapOf<Int, String>()
            val bytes = mutableMapOf<Int, ByteArray>()
            daten.forEach { (k, v) ->
                when (v) {
                    is Long -> zahlen[k] = v
                    is ULong -> zahlen[k] = v.toLong()
                    is Int -> zahlen[k] = v.toLong()
                    is UInt -> zahlen[k] = v.toLong()
                    is String -> texte[k] = v
                    is UByteArray -> bytes[k] = v.asByteArray()
                    is ByteArray -> bytes[k] = v
                }
            }
            return KieselNachricht(zahlen, texte, bytes)
        }
    }
}

/**
 * Die Begleiter, die Boulder fest mitbringt (siehe BuiltInCompanions in
 * libpebble3). Bisher einer: Kieselsport.
 */
class BoulderBegleiter(private val context: Context) : BuiltInCompanions {
    override fun create(uuid: Uuid, device: CompanionAppDevice): CompanionApp? = when (uuid) {
        Kieselsport.UUID -> KieselsportBegleiter(context, device)
        else -> null
    }
}
