package coredevices.pebble.kiesel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import coredevices.pebble.R
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 * Kiesel-Helper (OsmandNavigation).
 *
 * Die Angaben kommen ueber OsmAnds eigene Schnittstelle (AIDL), als Zahl und
 * nicht als Text einer Benachrichtigung. Angebunden wird nur, solange eine Uhr
 * verbunden ist - ohne Uhr gibt es niemanden, dem die Abzweigung etwas nuetzt.
 */
internal object Kieselstrasse {
    private const val TAG = "Kieselstrasse"

    val UUID: Uuid = Uuid.parse("a83bf269-e798-45bc-9bf0-a0f68362a79a")

    // Die Feldnummern der Uhr-App, in der Reihenfolge ihrer messageKeys ab 10000.
    const val ABBIEGEART = 10000   //< OsmAnds TurnType, 1..14
    const val ENTFERNUNG = 10001   //< Meter bis zur Abzweigung, -1 = keine
    const val STRASSE = 10002
    const val ANKUNFT = 10003      //< Sekunden seit 1970
    const val REST = 10004         //< Meter bis zum Ziel

    private const val AKTION = "net.osmand.aidl.OsmandAidlServiceV2"
    val PAKETE = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

    private const val ABFRAGE_MS = 2000L

    /**
     * Hoechstens so oft an die Uhr - OsmAnd meldet im Sekundentakt. Ein
     * Wechsel der Abbiegeart geht sofort: er ist der Augenblick, auf den es
     * ankommt.
     */
    private const val TAKT_MS = 4000L

    enum class Lage { NIE, VERBINDE, VERBUNDEN, ABONNIERT, BEENDET, UNERREICHBAR, FREISCHALTEN, ABO_FEHLER }

    @Volatile
    var lage: Lage = Lage.NIE
        private set

    @Volatile
    private var lageZusatz = ""

    private var api: IOsmAndAidlInterface? = null
    private var verbindung: ServiceConnection? = null
    private var app: Context? = null
    private var libPebble: LibPebble? = null
    private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var takt: Job? = null

    private var letzteArt = -1
    private var letzteMeter = -1
    private var letzterTakt = 0L
    @Volatile
    private var navigiert = false

    fun lageText(context: Context): String = when (lage) {
        Lage.NIE -> context.getString(R.string.ks_o_nie)
        Lage.VERBINDE -> context.getString(R.string.ks_o_verbinde, lageZusatz)
        Lage.VERBUNDEN -> context.getString(R.string.ks_o_verbunden, lageZusatz)
        Lage.ABONNIERT -> context.getString(R.string.ks_o_abonniert)
        Lage.BEENDET -> context.getString(R.string.ks_o_beendet)
        Lage.UNERREICHBAR -> context.getString(R.string.ks_o_unerreichbar)
        Lage.FREISCHALTEN -> context.getString(R.string.ks_o_freischalten)
        Lage.ABO_FEHLER -> context.getString(R.string.ks_o_abo_fehler, lageZusatz.ifBlank { context.getString(R.string.ks_o_unbekannt) })
    }

    private fun setzeLage(neu: Lage, zusatz: String = "") {
        lageZusatz = zusatz
        lage = neu
    }

    /** Welche OsmAnd-Fassung installiert ist, oder null. */
    fun osmandPaket(context: Context): String? =
        PAKETE.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    /** Bindet an OsmAnd, solange eine Uhr verbunden ist. Einmal beim Start der App. */
    fun begleite(context: Context, libPebble: LibPebble) {
        if (this.libPebble != null) return
        this.libPebble = libPebble
        val ctx = context.applicationContext
        bereich.launch {
            libPebble.watches
                .map { w -> w.any { it is ConnectedPebbleDevice } }
                .distinctUntilChanged()
                .collect { verbunden -> if (verbunden && osmandPaket(ctx) != null) binde(ctx) else loese(ctx) }
        }
    }

    /**
     * Nochmal anklopfen: das Freischalten geschieht in OsmAnd, und von selbst
     * fragt niemand ein zweites Mal nach dem Abonnement.
     */
    fun versucheErneut(context: Context) {
        if (api != null) abonniere(context.applicationContext) else binde(context.applicationContext)
    }

    private fun binde(ctx: Context) {
        if (api != null || verbindung != null) return
        app = ctx
        for (paket in PAKETE) {
            val verb = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    api = IOsmAndAidlInterface.Stub.asInterface(binder)
                    setzeLage(Lage.VERBUNDEN, paket)
                    KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_verbunden_v, paket))
                    abonniere(ctx)
                    starteTakt(ctx)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Android verbindet von selbst neu, sobald OsmAnd zurueck ist.
                    setzeLage(Lage.BEENDET)
                    api = null
                    navigiert = false
                }
            }
            if (ctx.bindService(Intent(AKTION).setPackage(paket), verb, Context.BIND_AUTO_CREATE)) {
                verbindung = verb
                setzeLage(Lage.VERBINDE, paket)
                return
            }
        }
        setzeLage(Lage.UNERREICHBAR)
    }

    private fun loese(ctx: Context) {
        val verb = verbindung ?: return
        try {
            ctx.unbindService(verb)
        } catch (e: IllegalArgumentException) {
            // War schon geloest.
        }
        verbindung = null
        api = null
        navigiert = false
        takt?.cancel()
        takt = null
        setzeLage(Lage.NIE)
    }

    private fun abonniere(ctx: Context) {
        val schnitt = api ?: return
        try {
            // -1 heisst: OsmAnd hat Boulder als verbundene App eingetragen,
            // aber ausgeschaltet - erst ein Schalter unter Plugins gibt sie frei.
            val nummer = schnitt.registerForNavigationUpdates(
                ANavigationUpdateParams().apply { setSubscribeToUpdates(true) },
                rueckruf,
            )
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

    // Nur als Anlass: die Werte holt immer frageAb, so gibt es einen Weg zu ihnen.
    private val rueckruf by lazy { object : IOsmAndAidlCallback.Stub() {
        override fun updateNavigationInfo(info: ADirectionInfo?) {
            app?.let { bereich.launch { frageAb(it) } }
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
     * Im Takt nachfragen: in Kiesel-Helper kam der Rueckruf auf dem Geraet nie
     * an, obwohl das Abonnement stand. getAppInfo liefert dasselbe auf Nachfrage.
     */
    private fun starteTakt(ctx: Context) {
        if (takt != null) return
        takt = bereich.launch {
            while (isActive) {
                frageAb(ctx)
                delay(ABFRAGE_MS)
            }
        }
    }

    @Synchronized
    private fun frageAb(ctx: Context) {
        val schnitt = api ?: return
        try {
            val info = schnitt.appInfo ?: return
            val kurve = info.turnInfo
            // "next_" ist die Abzweigung, die kommt; nur sie hat eine Entfernung.
            val meter = kurve?.getInt("next_turn_distance", -1) ?: -1
            if (kurve == null || meter <= 0) {
                if (navigiert) meldeEnde(ctx)
                return
            }
            val kuerzel = kurve.getString("next_turn_type")
            val nr = ausfahrt(kuerzel)
            val name = kurve.getString("next_turn_name").orEmpty()
            // Im Kreisel ist die Ausfahrt die Anweisung, der Name nur die Bestaetigung.
            val strasse = if (nr > 0) ctx.getString(R.string.ks_o_ausfahrt, nr) + (if (name.isBlank()) "" else " · $name") else name
            melde(meter, artAusText(kuerzel), strasse, info.arrivalTime, info.leftDistance)
        } catch (e: Exception) {
            Log.w(TAG, "Nachfrage fehlgeschlagen: " + e.message)
        }
    }

    private fun melde(meter: Int, art: Int, strasse: String, ankunft: Long, rest: Int) {
        navigiert = true
        if (art == letzteArt && meter == letzteMeter) return
        val jetzt = System.currentTimeMillis()
        val neuerSchritt = art != letzteArt
        if (!neuerSchritt && jetzt - letzterTakt < TAKT_MS) return
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
        sende(felder, starten = neuerSchritt)
    }

    /** Minus eins heisst "keine Zahl" - eine Null hiesse "genau hier abbiegen". */
    private fun meldeEnde(ctx: Context) {
        navigiert = false
        letzteArt = -1
        letzteMeter = -1
        sende(mapOf(ABBIEGEART to 0, ENTFERNUNG to -1, STRASSE to "", REST to -1), starten = false)
        KieselVerlauf.merke(ctx, ctx.getString(R.string.ks_o_ende))
    }

    /**
     * An die Uhr-App. Ein neuer Schritt startet sie: eine AppMessage erreicht
     * nur eine laufende App, und der Schirm bliebe sonst dunkel.
     */
    private val reihe = Mutex()

    private fun sende(felder: Map<Int, Any>, starten: Boolean) {
        val lp = libPebble ?: return
        bereich.launch { reihe.withLock {
            val uhr = lp.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull() ?: return@withLock
            try {
                if (uhr.runningApp.value != UUID) {
                    if (!starten) return@withLock
                    uhr.launchApp(UUID)
                    withTimeoutOrNull(5_000) { uhr.runningApp.first { it == UUID } }
                }
                uhr.sendAppMessage(AppMessageData(uhr.transactionSequence.next(), UUID, felder))
            } catch (e: Exception) {
                Log.w(TAG, "An die Uhr: " + e.message)
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

    /** Die Ausfahrtnummer aus "RNDB3" - 0, wenn keine dasteht. */
    fun ausfahrt(s: String?): Int {
        if (s == null || !(s.startsWith("RNDB") || s.startsWith("RNLB"))) return 0
        return s.drop(4).toIntOrNull() ?: 0
    }
}
