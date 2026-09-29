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
        arbeite()
        val app = context.applicationContext
        // Was liegen blieb, zuerst: Nachrichten, deren Verarbeitung scheiterte,
        // und Kurven, deren Eintrag scheiterte (etwa ohne Erlaubnis).
        Wartend.alle(app).forEach { auftraege.send(Auftrag(app, it)) }
        sitzung.launch { Pulskurve.alleEintragen(app) }
        sitzung.launch {
            incomingAppMessages.collect { nachricht ->
                // Die Zusammenfassung erst auf die Platte, dann bestaetigen
                // (siehe Wartend). NUR SIE: eine liegengebliebene Zustandsmeldung
                // startete beim naechsten Start das GPS fuer ein laengst
                // beendetes Training. Geht das Sichern nicht, wird trotzdem
                // bestaetigt und direkt verarbeitet - wie bisher.
                val n = zerlege(nachricht.data)
                val datei = if (n.zahlen.containsKey(Kieselsport.SP_DAUER)) Wartend.lege(app, n) else null
                device.sendAppMessageResult(AppMessageResult.ACK(nachricht.transactionId))
                auftraege.send(if (datei != null) Auftrag(app, datei) else Auftrag(app, nachricht = n))
            }
        }
    }

    override suspend fun stop() {
        // Nur der Empfang endet - was schon bestaetigt ist, wird fertig
        // verarbeitet (siehe unten).
        sitzung.cancel()
    }

    private class Auftrag(val context: Context, val datei: java.io.File? = null, val nachricht: KieselNachricht? = null)

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

        private var arbeitet = false

        // KEIN init-BLOCK (Regel in CLAUDE.md): der Arbeiter startet mit der
        // ersten Sitzung, nicht beim Laden der Klasse.
        @Synchronized
        fun arbeite() {
            if (arbeitet) return
            arbeitet = true
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for (a in auftraege) {
                    val n = a.nachricht ?: a.datei?.let { Wartend.lies(it) } ?: continue
                    try {
                        val meldung = Kieselsport.verarbeite(a.context, n)
                        meldung?.let { KieselVerlauf.merke(a.context, it) }
                        // Bleibt liegen, was die Akte nicht nahm - der naechste Start versucht es wieder.
                        if (!Kieselsport.gescheitert(a.context, meldung)) a.datei?.delete()
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
 * Hoert neben dem PebbleKit JS einer Uhr-App mit - fuer Drinktervall und
 * SupCycle, deren Meldungen frueher Kiesel-Helper ueber PebbleKit Android
 * bekam.
 *
 * ER BESTAETIGT NUR, WENN KEIN PEBBLEKIT JS LAEUFT: sonst tut das PebbleKit
 * JS der Uhr-App, und eine zweite Bestaetigung braucht niemand. Ohne
 * Netzwerk-Freigabe startet Gravel es aber nicht - dann wiederholte die Uhr
 * jede Nachricht, die niemand bestaetigt. Die Verarbeitung
 * laeuft wie bei Kieselsport in einer Warteschlange fuer die ganze App, damit
 * sie das Ende der Sitzung ueberlebt.
 */
internal class Mithoerer(
    private val context: Context,
    private val uuid: Uuid,
    private val device: CompanionAppDevice,
    private val bestaetigen: Boolean,
) : CompanionApp {
    private val sitzung = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun start(incomingAppMessages: Flow<AppMessageData>) {
        arbeite()
        sitzung.launch {
            incomingAppMessages.collect {
                if (bestaetigen) device.sendAppMessageResult(AppMessageResult.ACK(it.transactionId))
                auftraege.send(context.applicationContext to Gehoert(uuid, KieselsportBegleiter.zerlege(it.data)))
            }
        }
    }

    override suspend fun stop() {
        sitzung.cancel()
    }

    private class Gehoert(val uuid: Uuid, val nachricht: KieselNachricht)

    companion object {
        private val logger = Logger.withTag("Mithoerer")
        private val auftraege = Channel<Pair<Context, Gehoert>>(Channel.UNLIMITED)

        private var arbeitet = false

        // KEIN init-BLOCK (Regel in CLAUDE.md): der Arbeiter startet mit der
        // ersten Sitzung, nicht beim Laden der Klasse.
        @Synchronized
        fun arbeite() {
            if (arbeitet) return
            arbeitet = true
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for ((context, g) in auftraege) {
                    try {
                        when (g.uuid) {
                            Drinktervall.UUID -> Drinktervall.verarbeite(context, g.nachricht)
                            SupCycle.UUID -> SupCycle.verarbeite(context, g.nachricht)
                            else -> null
                        }?.let {
                            KieselVerlauf.merke(context, it)
                            GesundheitWidget.stosseAn(context)
                        }
                    } catch (e: Exception) {
                        logger.e(e) { "Nachricht von ${g.uuid} nicht verarbeitet: ${e.message}" }
                    }
                }
            }
        }
    }
}

/**
 * Die Begleiter, die Boulder fest mitbringt (siehe BuiltInCompanions in
 * libpebble3): Kieselsport ganz, Drinktervall und SupCycle neben ihrem
 * PebbleKit JS.
 */
class BoulderBegleiter(private val context: Context) : BuiltInCompanions {
    override fun create(uuid: Uuid, device: CompanionAppDevice): CompanionApp? = when (uuid) {
        Kieselsport.UUID -> KieselsportBegleiter(context, device)
        else -> null
    }

    override fun createBeside(uuid: Uuid, device: CompanionAppDevice, pkjsRunning: Boolean): CompanionApp? = when (uuid) {
        Drinktervall.UUID, SupCycle.UUID -> Mithoerer(context, uuid, device, bestaetigen = !pkjsRunning)
        else -> null
    }
}
