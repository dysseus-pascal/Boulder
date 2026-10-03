package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.BestaetigtSelbst
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
import kotlinx.coroutines.delay
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
        // Kurven, deren Eintrag scheiterte (etwa ohne Erlaubnis), und die
        // Kalorien aus Kurven, die schon in der Akte stehen.
        Wartend.alle(app).forEach { auftraege.send(Auftrag(app, it)) }
        sitzung.launch {
            Pulskurve.alleEintragen(app)
            Trainingswerte.holeKalorienNach(app)
        }
        sitzung.launch {
            incomingAppMessages.collect { nachricht ->
                // DIE ZEIT DES EMPFANGS, nicht die des Abarbeitens: hinter einem
                // langen Eintrag in die Akte kaeme ein Weiter Sekunden spaeter dran.
                val empfangen = System.currentTimeMillis() / 1000
                // Die Zusammenfassung erst auf die Platte, dann bestaetigen
                // (siehe Wartend). NUR SIE: eine liegengebliebene Zustandsmeldung
                // startete beim naechsten Start das GPS fuer ein laengst
                // beendetes Training. Geht das Sichern nicht, wird trotzdem
                // bestaetigt und direkt verarbeitet - wie bisher.
                val n = zerlege(nachricht.data)
                val datei = if (n.zahlen.containsKey(Kieselsport.SP_DAUER)) Wartend.lege(app, n) else null
                device.sendAppMessageResult(AppMessageResult.ACK(nachricht.transactionId))
                auftraege.send(if (datei != null) Auftrag(app, datei, empfangen = empfangen) else Auftrag(app, nachricht = n, empfangen = empfangen))
            }
        }
    }

    override suspend fun stop() {
        // Nur der Empfang endet - was schon bestaetigt ist, wird fertig
        // verarbeitet (siehe unten).
        sitzung.cancel()
    }

    /** [empfangen]: Epochensekunden; null bei einer Datei vom letzten Mal - dann zaehlt ihre Ablage. */
    private class Auftrag(
        val context: Context, val datei: java.io.File? = null, val nachricht: KieselNachricht? = null, val empfangen: Long? = null,
    )

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
                    val empfangen = a.empfangen ?: a.datei?.let { Wartend.abgelegt(it.name) } ?: (System.currentTimeMillis() / 1000)
                    try {
                        val meldung = Kieselsport.verarbeite(a.context, n, empfangen)
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
 * Was eine Meldung von Drinktervall oder SupCycle ergab: der Satz fuers
 * Protokoll, und ob die Akte etwas nicht nahm. [rest]: was davon noch
 * fehlt, wenn nur ein Teil scheiterte - null heisst die ganze Meldung.
 */
internal class Verarbeitet(val meldung: String?, val gescheitert: Boolean = false, val rest: KieselNachricht? = null)

/**
 * Hoert neben dem PebbleKit JS einer Uhr-App mit - fuer Drinktervall und
 * SupCycle, deren Meldungen frueher Kiesel-Helper ueber PebbleKit Android
 * bekam. Die Verarbeitung laeuft wie bei Kieselsport in einer Warteschlange
 * fuer die ganze App, damit sie das Ende der Sitzung ueberlebt.
 *
 * ERST SICHERN, DANN BESTAETIGEN, wie bei Kieselsport (siehe Wartend): die
 * Uhr nimmt Glas und Kaffee mit der Bestaetigung aus ihrer Schlange. Was die
 * Akte nicht nahm, bleibt liegen und kommt beim naechsten Start dran.
 * Darum bestaetigt er immer selbst, auch neben PebbleKit JS ([BestaetigtSelbst]).
 */
internal class Mithoerer(
    private val context: Context,
    private val uuid: Uuid,
    private val device: CompanionAppDevice,
) : CompanionApp, BestaetigtSelbst {
    private val sitzung = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun start(incomingAppMessages: Flow<AppMessageData>) {
        arbeite()
        val app = context.applicationContext
        Wartend.alle(app, ablage(uuid), hoechstensTage = VERFALL_TAGE).forEach { auftraege.send(app to Gehoert(uuid, datei = it, nachgeholt = true)) }
        sitzung.launch {
            incomingAppMessages.collect {
                val n = KieselsportBegleiter.zerlege(it.data)
                // Ein SupCycle-Stand ersetzt den vorigen desselben Tages ganz; der eines
                // frueheren Tages bleibt, bis er nachgetragen ist. Glaeser und Kaffees sind je ein eigener Eintrag.
                val ersetzt: ((KieselNachricht) -> Boolean)? = if (uuid == SupCycle.UUID) { alt -> SupCycle.selberTag(alt, n) } else null
                val datei = if (aufheben(uuid, n)) Wartend.lege(app, n, ablage(uuid), ersetzt) else null
                device.sendAppMessageResult(AppMessageResult.ACK(it.transactionId))
                auftraege.send(app to if (datei != null) Gehoert(uuid, datei = datei) else Gehoert(uuid, nachricht = n))
            }
        }
        if (uuid == SupCycle.UUID) sitzung.launch { ruecknahmen(app) }
    }

    /** Haken zuruecknehmen (SupCycle.verwirf) - eine AppMessage erreicht nur die laufende Uhr-App. */
    private suspend fun ruecknahmen(app: Context) {
        // Die Uhr-App oeffnet ihren Eingang erst beim Start - davor kaeme ein NACK.
        delay(1_500)
        sendeRuecknahme(app)
        SupCycle.anstoss.collect { sendeRuecknahme(app) }
    }

    /**
     * Die Bestaetigung heisst nur "angekommen". Erledigt ist der Befehl erst mit
     * dem Stand, den die Uhr danach schickt (SupCycle.nochOffen) - bis dahin geht
     * er bei jedem Start und Anstoss wieder hinaus. Die Uhr nimmt nur Haken mit
     * der gemeinten Zeit zurueck, doppelt schadet also nicht.
     */
    private suspend fun sendeRuecknahme(app: Context) {
        for (versuch in 1..3) {
            val r = SupCycle.ruecknahme(app) ?: return
            val ergebnis = try {
                device.sendAppMessage(AppMessageData(device.transactionSequence.next(), uuid, r.felder()))
            } catch (e: Exception) {
                logger.w { "Ruecknahme an SupCycle: ${e.message}" }
                return
            }
            if (ergebnis is AppMessageResult.ACK) return
            delay(1_000)
        }
    }

    override suspend fun stop() {
        sitzung.cancel()
    }

    private class Gehoert(val uuid: Uuid, val datei: java.io.File? = null, val nachricht: KieselNachricht? = null, val nachgeholt: Boolean = false)

    companion object {
        private val logger = Logger.withTag("Mithoerer")
        private val auftraege = Channel<Pair<Context, Gehoert>>(Channel.UNLIMITED)

        private var arbeitet = false

        private fun ablage(uuid: Uuid) = "uhrapp-wartend-$uuid"

        /** So lange wartet ein Glas oder Praeparat auf die Akte - die Seite zeigt ohnehin nur die letzten Wochen. */
        private const val VERFALL_TAGE = 30L

        /** Nur was einen Eintrag fuer die Akte traegt - eine blosse Zielmeldung kommt ohnehin wieder. */
        private fun aufheben(uuid: Uuid, n: KieselNachricht): Boolean = when (uuid) {
            Drinktervall.UUID -> Drinktervall.traegtEintrag(n)
            SupCycle.UUID -> SupCycle.SC_TODAY in n.zahlen
            else -> false
        }

        // KEIN init-BLOCK (Regel in CLAUDE.md): der Arbeiter startet mit der
        // ersten Sitzung, nicht beim Laden der Klasse.
        @Synchronized
        fun arbeite() {
            if (arbeitet) return
            arbeitet = true
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for ((context, g) in auftraege) {
                    // Ein juengerer SupCycle-Stand kann die Datei schon ersetzt haben.
                    val n = g.nachricht ?: g.datei?.takeIf { it.exists() }?.let { Wartend.lies(it) } ?: continue
                    try {
                        val e = when (g.uuid) {
                            Drinktervall.UUID -> Drinktervall.verarbeite(context, n, nachgeholt = g.nachgeholt)
                            SupCycle.UUID -> SupCycle.verarbeite(context, n)
                            else -> null
                        }
                        // Ein erneut gescheiterter Nachtrag stand schon einmal im Protokoll.
                        e?.meldung?.takeUnless { g.nachgeholt && e.gescheitert }?.let {
                            KieselVerlauf.merke(context, it)
                            GesundheitWidget.stosseAn(context)
                            Trenddaten.eintragGeschrieben()
                        }
                        val rest = e?.takeIf { it.gescheitert }?.rest
                        // Nur was scheiterte, bleibt liegen: ein schon eingetragener Kaffee kaeme
                        // sonst bei jedem Start wieder - auch nachdem er geloescht oder geaendert ist.
                        if (e?.gescheitert != true) g.datei?.delete()
                        else if (rest != null) g.datei?.let { Wartend.ersetze(it, rest) }
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
        Drinktervall.UUID, SupCycle.UUID -> Mithoerer(context, uuid, device)
        else -> null
    }
}
