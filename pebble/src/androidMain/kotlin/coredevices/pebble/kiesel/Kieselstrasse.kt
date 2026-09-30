package coredevices.pebble.kiesel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import coredevices.pebble.R
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.ANavigationUpdateParams
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult
import kotlin.uuid.Uuid

/**
 * Kieselstrasse: OsmAnds naechste Abzweigung auf der Uhr - wie in
 * Kiesel-Helper (OsmandNavigation), heute eine System-App der Firmware.
 *
 * Die Angaben kommen ueber OsmAnds eigene Schnittstelle (AIDL), als Zahl und
 * nicht als Text einer Benachrichtigung. AUF ABRUF: angebunden wird nur,
 * solange Kieselstrasse auf der Uhr offen ist (siehe [StrassenAbruf]); die
 * Uhr-App oeffnet sich nicht mehr von selbst.
 */
internal object Kieselstrasse {
    private const val TAG = "Kieselstrasse"

    val UUID: Uuid = Uuid.parse("a83bf269-e798-45bc-9bf0-a0f68362a79a")

    // Die Feldnummern der Uhr-App (schluessel.h der Firmware), fest ab 10000.
    const val ABBIEGEART = 10000   //< OsmAnds TurnType, 1..14
    const val ENTFERNUNG = 10001   //< Meter bis zur Abzweigung, -1 = keine
    const val STRASSE = 10002
    const val ANKUNFT = 10003      //< Sekunden seit 1970
    const val REST = 10004         //< Meter bis zum Ziel
    // Nur im Kreisverkehr. Eine aeltere Uhr-Firmware kennt sie nicht und laesst sie liegen.
    const val AUSFAHRT = 10005     //< Nummer der Ausfahrt, 1, 2, 3 ...
    const val WINKEL = 10006       //< OsmAnds turn_angle in Grad, 0 geradeaus, + rechts

    private const val AKTION = "net.osmand.aidl.OsmandAidlServiceV2"
    val PAKETE = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

    /**
     * Hoechstens so oft an die Uhr - OsmAnd meldet im Sekundentakt. Ein
     * Wechsel der Abbiegeart geht sofort: er ist der Augenblick, auf den es
     * ankommt.
     */
    private const val TAKT_MS = 4000L

    enum class Lage { NIE, VERBINDE, VERBUNDEN, ABONNIERT, BEREIT, BEENDET, UNERREICHBAR, FREISCHALTEN, ABO_FEHLER }

    data class Stand(val lage: Lage, val zusatz: String = "")

    // Als Fluss, weil die Pruefung aus den Einstellungen erst nach dem Zeichnen fertig wird.
    private val _stand = MutableStateFlow(Stand(Lage.NIE))
    val stand: StateFlow<Stand> = _stand.asStateFlow()
    val lage: Lage get() = _stand.value.lage

    @Volatile private var api: IOsmAndAidlInterface? = null
    private var verbindung: ServiceConnection? = null
    private var aboNummer = -1L
    private var app: Context? = null
    private var libPebble: LibPebble? = null
    private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Die Meldungen der Bindung in ihrer Reihenfolge: "weg" darf "da" nicht ueberholen.
    private val folge = Dispatchers.IO.limitedParallelism(1)
    private val abruf = StrassenAbruf()
    private var takt: Job? = null
    private var taktMs: Long? = null
    private var wecker: Job? = null

    private var letzteArt = -1
    private var letzteMeter = -1
    private var letzterTakt = 0L
    // Die erste Antwort nach dem Oeffnen geht auch ohne Navigation an die Uhr:
    // sie zeigt sonst den gespeicherten Schritt einer laengst beendeten Fahrt.
    private var ersteAntwort = false
    private var automatischGeprueft = false

    fun lageText(context: Context, s: Stand = stand.value): String = when (s.lage) {
        Lage.NIE -> context.getString(R.string.ks_o_nie)
        Lage.VERBINDE -> context.getString(R.string.ks_o_verbinde, s.zusatz)
        Lage.VERBUNDEN -> context.getString(R.string.ks_o_verbunden, s.zusatz)
        Lage.ABONNIERT -> context.getString(R.string.ks_o_abonniert)
        Lage.BEREIT -> context.getString(R.string.ks_o_bereit)
        Lage.BEENDET -> context.getString(R.string.ks_o_beendet)
        Lage.UNERREICHBAR -> context.getString(R.string.ks_o_unerreichbar)
        Lage.FREISCHALTEN -> context.getString(R.string.ks_o_freischalten)
        Lage.ABO_FEHLER -> context.getString(R.string.ks_o_abo_fehler, s.zusatz.ifBlank { context.getString(R.string.ks_o_unbekannt) })
    }

    private fun setzeLage(neu: Lage, zusatz: String = "") {
        _stand.value = Stand(neu, zusatz)
    }

    /** Welche OsmAnd-Fassung installiert ist, oder null. */
    fun osmandPaket(context: Context): String? =
        PAKETE.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    /** Folgt der laufenden App der Uhr. Einmal beim Start der App. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun begleite(context: Context, libPebble: LibPebble) {
        if (this.libPebble != null) return
        this.libPebble = libPebble
        val ctx = context.applicationContext
        app = ctx
        bereich.launch {
            libPebble.watches
                // Die Liste kommt bei jeder Akkuzahl neu; die laufende App haengt nur an der Verbindung.
                .map { w -> w.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()?.runningApp }
                .distinctUntilChanged()
                .flatMapLatest { it ?: flowOf(null) }
                .map { it == UUID }
                .distinctUntilChanged()
                .collect { offen -> uhrApp(ctx, offen) }
        }
    }

    /**
     * Aus den Einstellungen: ist OsmAnd erreichbar und freigeschaltet? Das
     * Freischalten geschieht in OsmAnd, und von selbst fragt niemand ein
     * zweites Mal nach dem Abonnement. Ohne offene Uhr-App wird dafuer kurz
     * gebunden und wieder geloest.
     */
    fun pruefe(context: Context) {
        val ctx = context.applicationContext
        bereich.launch { pruefeJetzt(ctx) }
    }

    /**
     * Beim Oeffnen der Einstellungen: einmal je Prozess. Die Liste erscheint
     * bei jedem Blick in die Einstellungen, und jede Pruefung weckt OsmAnd.
     */
    fun pruefeEinmal(context: Context) {
        if (automatischGeprueft) return
        automatischGeprueft = true
        if (lage != Lage.BEREIT && lage != Lage.ABONNIERT) pruefe(context)
    }

    @Synchronized
    private fun pruefeJetzt(ctx: Context) {
        if (app == null) app = ctx
        if (api != null) {
            abonniere(ctx)
            return
        }
        abruf.pruefe(jetzt())
        stimme(ctx)
    }

    @Synchronized
    private fun uhrApp(ctx: Context, offen: Boolean) {
        if (abruf.uhrApp(offen, jetzt())) {
            // Die Uhr-App ist neu gestartet und kennt nur ihren gespeicherten Stand.
            letzteArt = -1
            letzteMeter = -1
            letzterTakt = 0L
            ersteAntwort = true
            if (api != null) bereich.launch { nachDemBinden(ctx) }
        }
        stimme(ctx)
    }

    // Monoton: eine Zeitumstellung am Telefon soll Karenz und Pruefung nicht verschieben.
    private fun jetzt() = SystemClock.elapsedRealtime()

    /** Bringt Bindung, Nachfragetakt und Wecker in den Stand, den [abruf] verlangt. */
    @Synchronized
    private fun stimme(ctx: Context) {
        val jetzt = jetzt()
        val soll = abruf.soll(jetzt)
        if (soll.binden) binde(ctx) else loese(ctx)
        if (soll.taktMs != taktMs) {
            takt?.cancel()
            taktMs = soll.taktMs
            takt = soll.taktMs?.let { ms ->
                bereich.launch {
                    while (isActive) {
                        delay(ms)
                        frageAb(ctx)
                    }
                }
            }
        }
        wecker?.cancel()
        wecker = abruf.naechsteWende(jetzt)?.let { wann ->
            bereich.launch {
                delay(wann - jetzt)
                stimme(ctx)
            }
        }
    }

    private fun binde(ctx: Context) {
        if (api != null || verbindung != null) return
        for (paket in PAKETE) {
            // Alles nicht auf dem Hauptfaden: es folgen Aufrufe in einen fremden Prozess.
            val verb = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val selbst = this
                    bereich.launch(folge) { verbunden(ctx, selbst, paket, binder) }
                }

                // Android verbindet von selbst neu, sobald OsmAnd zurueck ist.
                override fun onServiceDisconnected(name: ComponentName?) {
                    val selbst = this
                    bereich.launch(folge) { verloren(ctx, selbst) }
                }

                // Nach einem Update oder Stopp von OsmAnd dagegen nie wieder.
                override fun onBindingDied(name: ComponentName?) {
                    val selbst = this
                    bereich.launch(folge) { abgerissen(ctx, selbst, neuBinden = true) }
                }

                override fun onNullBinding(name: ComponentName?) {
                    val selbst = this
                    bereich.launch(folge) { abgerissen(ctx, selbst, neuBinden = false) }
                }
            }
            // WAIVE_PRIORITY: Boulder soll OsmAnd nicht wichtiger machen, als es ohnehin ist.
            if (ctx.bindService(Intent(AKTION).setPackage(paket), verb, Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY)) {
                verbindung = verb
                setzeLage(Lage.VERBINDE, paket)
                return
            }
        }
        setzeLage(Lage.UNERREICHBAR)
        abruf.geprueft()
    }

    @Synchronized
    private fun nachDemBinden(ctx: Context) {
        abonniere(ctx)
        abruf.geprueft()
        if (abruf.offen) frageAb(ctx)
        stimme(ctx)
    }

    // Eine verspaetete Meldung einer schon geloesten Bindung darf nichts mehr setzen.
    @Synchronized
    private fun verbunden(ctx: Context, verb: ServiceConnection, paket: String, binder: IBinder?) {
        if (verbindung !== verb) return
        api = IOsmAndAidlInterface.Stub.asInterface(binder)
        setzeLage(Lage.VERBUNDEN, paket)
        KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_verbunden_v, paket))
        nachDemBinden(ctx)
    }

    @Synchronized
    private fun verloren(ctx: Context, verb: ServiceConnection) {
        if (verbindung !== verb) return
        setzeLage(Lage.BEENDET)
        api = null
        aboNummer = -1L
        beendeNavigation(ctx)
        stimme(ctx)
    }

    /** Die Bindung ist tot oder OsmAnd gibt keine Schnittstelle heraus: loesen, statt ewig zu warten. */
    @Synchronized
    private fun abgerissen(ctx: Context, verb: ServiceConnection, neuBinden: Boolean) {
        if (verbindung !== verb) return
        beendeNavigation(ctx)
        loese(ctx)
        if (!neuBinden) {
            setzeLage(Lage.UNERREICHBAR)
            return
        }
        // Kurz warten: OsmAnd wird gerade ersetzt oder beendet.
        bereich.launch {
            delay(1_000)
            stimme(ctx)
        }
    }

    // Ohne OsmAnd keine Route mehr: sonst bliebe der letzte Pfeil auf der Uhr stehen.
    private fun beendeNavigation(ctx: Context) {
        if (!abruf.navigation(false)) return
        KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_ende))
        meldeEnde()
    }

    private fun loese(ctx: Context) {
        val verb = verbindung ?: return
        abbestelle()
        try {
            ctx.unbindService(verb)
        } catch (e: IllegalArgumentException) {
            // War schon geloest.
        }
        verbindung = null
        api = null
        KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_geloest_v, abruf.anzahlRueckrufe))
        abruf.geloest()
        // Was die Pruefung ergab, bleibt fuer die Einstellungen stehen.
        setzeLage(when (lage) {
            Lage.ABONNIERT -> Lage.BEREIT
            Lage.FREISCHALTEN, Lage.ABO_FEHLER, Lage.UNERREICHBAR -> lage
            else -> Lage.NIE
        })
    }

    @Synchronized
    private fun abonniere(ctx: Context) {
        val schnitt = api ?: return
        if (aboNummer >= 0) return
        try {
            // -1 heisst: OsmAnd hat Boulder als verbundene App eingetragen,
            // aber ausgeschaltet - erst ein Schalter unter Plugins gibt sie frei.
            val nummer = schnitt.registerForNavigationUpdates(
                ANavigationUpdateParams().apply { setSubscribeToUpdates(true) },
                rueckruf,
            )
            aboNummer = nummer
            if (nummer < 0) {
                setzeLage(Lage.FREISCHALTEN)
                KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_freischalten_v))
            } else {
                setzeLage(Lage.ABONNIERT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Abonnieren fehlgeschlagen: " + e.message)
            setzeLage(Lage.ABO_FEHLER, e.message.orEmpty())
        }
    }

    // Sonst hielte OsmAnd den Rueckruf einer Bindung fest, die es nicht mehr gibt.
    private fun abbestelle() {
        val schnitt = api ?: return
        if (aboNummer < 0) return
        try {
            schnitt.registerForNavigationUpdates(
                ANavigationUpdateParams().apply {
                    setSubscribeToUpdates(false)
                    callbackId = aboNummer
                },
                rueckruf,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Abbestellen fehlgeschlagen: " + e.message)
        }
        aboNummer = -1L
    }

    // Nur als Anlass: die Werte holt immer frageAb, so gibt es einen Weg zu ihnen.
    private val rueckruf by lazy { object : IOsmAndAidlCallback.Stub() {
        override fun updateNavigationInfo(info: ADirectionInfo?) {
            app?.let { bereich.launch { rueckrufKam(it) } }
        }
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
        override fun onKeyEvent(params: KeyEvent?) {}
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {}
    } }

    /**
     * In Kiesel-Helper kam der Rueckruf auf dem Geraet nie an, obwohl das
     * Abonnement stand. Ob er es in Boulder tut, steht im Verlauf: der erste
     * je Bindung wird vermerkt, die Zahl beim Loesen.
     */
    @Synchronized
    private fun rueckrufKam(ctx: Context) {
        if (abruf.rueckruf()) {
            KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_rueckruf_v))
            stimme(ctx)
        }
        if (abruf.offen) frageAb(ctx)
    }

    /** getAppInfo liefert, was der Rueckruf meldet, auf Nachfrage. */
    @Synchronized
    private fun frageAb(ctx: Context) {
        val schnitt = api ?: return
        try {
            val info = schnitt.appInfo ?: return
            val kurve = info.turnInfo
            // "next_" ist die Abzweigung, die kommt; nur sie hat eine Entfernung.
            val meter = kurve?.getInt("next_turn_distance", -1) ?: -1
            if (kurve == null || meter <= 0) {
                // Auch direkt an der Abzweigung kann eine leere Antwort kommen; erst mehrere gelten als Ende.
                val ende = abruf.leereAntwort()
                if (ende) KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_ende))
                if (ende || ersteAntwort) meldeEnde()
                ersteAntwort = false
                if (ende) stimme(ctx)
                return
            }
            ersteAntwort = false
            val kuerzel = kurve.getString("next_turn_type")
            val art = artAusText(kuerzel)
            val nr = ausfahrt(kuerzel)
            val name = kurve.getString("next_turn_name").orEmpty()
            // Im Kreisel ist die Ausfahrt die Anweisung, der Name nur die Bestaetigung.
            // Die Nummer bleibt auch im Text: eine Uhr ohne AUSFAHRT zeigt sie sonst nirgends.
            val strasse = if (nr > 0) ctx.getString(R.string.ks_o_ausfahrt, nr) + (if (name.isBlank()) "" else " · $name") else name
            // Wohin die Ausfahrt zeigt - nur OsmAnds Winkel weiss das, die Nummer nicht.
            // Auch ohne Nummer: die Richtung der Ausfahrt zeigt die Uhr dann trotzdem.
            val kreisel = art == 13 || art == 14
            val winkel = if (kreisel && kurve.containsKey("next_turn_angle")) winkelFuerUhr(kurve.getFloat("next_turn_angle")) else null
            melde(meter, art, strasse, info.arrivalTime, info.leftDistance, nr, winkel)
            if (abruf.navigation(true)) stimme(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "Nachfrage fehlgeschlagen: " + e.message)
        }
    }

    private fun melde(meter: Int, art: Int, strasse: String, ankunft: Long, rest: Int, ausfahrt: Int, winkel: Int?) {
        if (art == letzteArt && meter == letzteMeter) return
        val jetzt = jetzt()
        if (art == letzteArt && jetzt - letzterTakt < TAKT_MS) return
        letzteArt = art
        letzteMeter = meter
        letzterTakt = jetzt

        val felder = mutableMapOf<Int, Any>(
            ABBIEGEART to art,
            ENTFERNUNG to meter,
            // Leer loescht auf der Uhr die Strasse der vorigen Abzweigung.
            STRASSE to strasse,
        )
        if (ankunft > 0) felder[ANKUNFT] = ankunft.toInt()
        if (rest > 0) felder[REST] = rest
        if (ausfahrt > 0) felder[AUSFAHRT] = ausfahrt
        if (winkel != null) felder[WINKEL] = winkel
        sende(felder)
    }

    /** Minus eins heisst "keine Zahl" - eine Null hiesse "genau hier abbiegen". */
    private fun meldeEnde() {
        letzteArt = -1
        letzteMeter = -1
        sende(mapOf(ABBIEGEART to 0, ENTFERNUNG to -1, STRASSE to "", REST to -1))
    }

    private val reihe = Mutex()

    /** An die Uhr-App, nur wenn sie offen ist - eine AppMessage erreicht nur eine laufende App. */
    private fun sende(felder: Map<Int, Any>) {
        val lp = libPebble ?: return
        bereich.launch { reihe.withLock {
            val uhr = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull() ?: return@withLock
            if (uhr.runningApp.value != UUID) return@withLock
            for (versuch in 1..3) {
                if (uhr.runningApp.value != UUID) return@withLock
                val ab = SystemClock.elapsedRealtime()
                val ergebnis = try {
                    uhr.sendAppMessage(AppMessageData(uhr.transactionSequence.next(), UUID, felder))
                } catch (e: Exception) {
                    Log.w(TAG, "An die Uhr: " + e.message)
                    return@withLock
                }
                // Ein schnelles NACK kommt von der Uhr-App, die ihren Eingang gerade erst oeffnet.
                // Nach dem Zeitlimit ist die Uhr fort, dann hilft kein zweiter Versuch.
                if (ergebnis is AppMessageResult.ACK || SystemClock.elapsedRealtime() - ab > 2_000) return@withLock
                delay(500)
            }
        } }
    }

    /**
     * OsmAnds Kuerzel als Kennzahl der Uhr-App. Kreisverkehre tragen die
     * Ausfahrt mit ("RNDB3"), deshalb zaehlt dort nur der Anfang.
     */
    fun artAusText(s: String?): Int = when {
        s == null -> 0
        s.startsWith("RNDB") -> 13
        s.startsWith("RNLB") -> 14
        else -> listOf("C", "TL", "TSLL", "TSHL", "TR", "TSLR", "TSHR", "KL", "KR", "TU", "TRU", "OFFR").indexOf(s) + 1
    }

    /** OsmAnds Winkel ganzzahlig und in -180..180, wie die Uhr ihn annimmt; null, wenn keiner. */
    fun winkelFuerUhr(grad: Float): Int? {
        if (grad.isNaN() || grad.isInfinite()) return null
        var w = Math.round(grad) % 360
        if (w > 180) w -= 360
        if (w < -180) w += 360
        return w
    }

    /** Die Ausfahrtnummer aus "RNDB3" - 0, wenn keine dasteht. */
    fun ausfahrt(s: String?): Int {
        if (s == null || !(s.startsWith("RNDB") || s.startsWith("RNLB"))) return 0
        return s.drop(4).toIntOrNull() ?: 0
    }
}
