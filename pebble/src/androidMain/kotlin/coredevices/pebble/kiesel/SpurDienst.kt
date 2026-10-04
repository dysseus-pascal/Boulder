package coredevices.pebble.kiesel

import coredevices.pebble.R

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import co.touchlab.kermit.Logger
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Schreibt mit, wo man während eines Trainings war.
 *
 * ER LÄUFT NUR, SOLANGE EIN TRAINING LÄUFT. Das ist der ganze Unterschied zu
 * einer App, die dauernd den Standort kennt: Kieselsport meldet den Start, der
 * Dienst geht an; es meldet das Ende, der Dienst geht aus. Dazwischen liegt
 * genau die Zeit, für die jemand eine Strecke sehen will.
 *
 * EIN VORDERGRUNDDIENST MIT SICHTBARER MELDUNG, und das ist keine Formalie:
 * Android verlangt sie für Ortung im Hintergrund, und wer den Standort eines
 * Menschen aufzeichnet, soll das nicht lautlos tun können.
 *
 * KEIN GOOGLE-STANDORTDIENST. Der LocationManager des Systems genügt, kostet
 * keine Abhängigkeit und läuft auch auf einem Telefon ohne Play-Dienste.
 *
 * ER DARF NIEMALS ABSTÜRZEN. Gestartet wird er von einer Rundmeldung der Uhr,
 * also aus dem Nichts und oft mit dunklem Schirm. Beim ersten Versuch riss ein
 * Absturz hier die ganze App mit: Android prüft die Standortberechtigung seit
 * 14 INNERHALB von startForeground und wirft, statt Nein zu sagen. Deshalb
 * wird vorher gefragt und alles Übrige gefangen.
 */
internal class SpurDienst : android.app.Service(), LocationListener {

    private var beginn: Long = 0
    private var laeuft = false

    /** Ein Stopp wartet auf den Rest des GPS-Puffers (siehe [halt]). */
    private var haelt = false

    /**
     * Beim Wechsel auf ein neues Training der Beginn des alten, bis dessen
     * GPS-Puffer (bis 60 s) geholt ist; 0 sonst. Siehe [Spurwechsel].
     */
    private var frueher = 0L

    private val hand = Handler(Looper.getMainLooper())
    private val nachlaufVorbei = Runnable { if (haelt) beende() }
    private val wechselVorbei = Runnable { wechselFertig() }

    /**
     * Wie dicht gemessen wird.
     *
     * Drei Sekunden und fünf Meter: dichter macht die Linie nicht genauer,
     * nur die Datei grösser und den Akku leerer. Ein Läufer legt in drei
     * Sekunden gut zehn Meter zurück.
     */
    private val takt = 3000L
    private val abstand = 5f

    override fun onCreate() {
        super.onCreate()
        meldungskanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AUS) {
            // EIN STOPP FUER EIN ANDERES TRAINING haelt dieses nicht an: die
            // Zusammenfassung oder der Stopp eines frueheren kann spaet kommen.
            val fuer = intent.getLongExtra(EXTRA_BEGINN, 0L)
            if (fuer > 0L && laeuft && beginn > 0L && fuer != beginn) return START_NOT_STICKY
            halt()
            return START_NOT_STICKY
        }

        // ZUERST DIE ERLAUBNIS, DANN DER VORDERGRUND. Andersherum wirft das
        // System, bevor irgendeine eigene Prüfung greift: ein Dienst der Art
        // location darf ohne Standortberechtigung gar nicht erst in den
        // Vordergrund gehen.
        if (!darfOrten(this)) {
            logger.w { "Keine Standortberechtigung - Spur entfällt" }
            stopSelf()
            return START_NOT_STICKY
        }

        val vorher = beginn
        beginn = intent?.getLongExtra(EXTRA_BEGINN, 0L) ?: 0L
        if (beginn <= 0L) beginn = System.currentTimeMillis() / 1000
        if (!starteVordergrund(intent?.getStringExtra(EXTRA_ART) ?: getString(R.string.ks_training))) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Weiter kam, waehrend ein Stopp noch den Puffer holt: weiter orten.
        if (haelt) {
            haelt = false
            hand.removeCallbacks(nachlaufVorbei)
        }
        // Ein neues Training auf demselben Dienst (der Stopp des alten blieb aus):
        // erst dessen Puffer in seine Datei.
        if (laeuft && beginn != vorher && !holePufferFuer(vorher)) aufnahme.value = beginn
        horche()

        // NICHT STICKY. Startet Android den Dienst nach einem Abschuss neu,
        // wuesste er weder, welches Training laeuft, noch ob ueberhaupt eines
        // laeuft - und zeichnete ins Leere.
        return START_NOT_STICKY
    }

    private fun starteVordergrund(art: String): Boolean {
        // Tippen oeffnet Boulder - welche Aktivitaet das ist, weiss das System.
        val tippen = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val meldung: Notification = Notification.Builder(this, KANAL)
            .setContentTitle(getString(R.string.ks_sp_titel, art))
            .setContentText(getString(R.string.ks_sp_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(tippen)
            .setOngoing(true)
            .build()

        return try {
            // Ab Android 14 muss die Art des Dienstes beim Start mitgegeben
            // werden; ohne sie wirft das System eine Ausnahme.
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, MELDUNG_ID, meldung,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(MELDUNG_ID, meldung)
            }
            true
        } catch (e: Exception) {
            // DAS IST KEIN FEHLER, DEN MAN VERSCHWEIGEN DARF. Wer nach dem
            // Lauf eine leere Karte sieht, soll nachlesen können, warum.
            logger.w { "Vordergrunddienst: " + e.message }
            KieselVerlauf.merke(
                this, getString(R.string.ks_sp_nicht_zugelassen, e.message ?: e.javaClass.simpleName)
            )
            false
        }
    }

    private fun horche() {
        if (laeuft) return
        val verwalter = getSystemService(LocationManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // GEBUENDELT: Takt, Abstand und Genauigkeit wie die alte Anfrage, aber das
                // GPS sammelt bis zu einer Minute - ~60 Weckungen je Stunde, vorher ~1200.
                val anfrage = LocationRequest.Builder(takt)
                    .setMinUpdateIntervalMillis(takt)
                    .setMinUpdateDistanceMeters(abstand)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMaxUpdateDelayMillis(BUENDEL_MS)
                    .build()
                verwalter.requestLocationUpdates(LocationManager.GPS_PROVIDER, anfrage, mainExecutor, this)
            } else {
                verwalter.requestLocationUpdates(LocationManager.GPS_PROVIDER, takt, abstand, this)
            }
            laeuft = true
            aufnahme.value = beginn
        } catch (e: Exception) {
            logger.w { "Ortung: " + e.message }
            beende()
        }
    }

    /**
     * Anhalten - ERST DEN PUFFER HOLEN: removeUpdates verwuerfe bis zu einer
     * Minute Punkte. Der Dienst endet in [onFlushComplete], spaetestens nach [NACHLAUF_MS].
     */
    private fun halt() {
        if (!laeuft) return beende()
        if (haelt) return
        haelt = true
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                getSystemService(LocationManager::class.java)?.requestFlush(LocationManager.GPS_PROVIDER, this, NACHLAUF_CODE)
                hand.postDelayed(nachlaufVorbei, NACHLAUF_MS)
                return
            } catch (e: Exception) {
                logger.w { "Puffer holen: " + e.message }
            }
        }
        beende()
    }

    /**
     * Den Puffer des Trainings [alt] holen, bevor die Punkte ins neue gehen.
     * OHNE DAS landeten bis zu 60 s seiner Strecke (vor dem Buendeln ein Punkt)
     * in der Datei des neuen. Bis [onFlushComplete], hoechstens [NACHLAUF_MS], gehen sie nach [alt].
     * false, wenn nichts angefordert wurde (vor Android 12 gibt es keinen Puffer).
     */
    private fun holePufferFuer(alt: Long): Boolean {
        if (Build.VERSION.SDK_INT < 31 || alt <= 0L) return false
        val verwalter = getSystemService(LocationManager::class.java) ?: return false
        return try {
            // Vor dem Anfordern: der Puffer kommt ueber denselben Hauptfaden, also erst danach.
            frueher = alt
            hand.removeCallbacks(wechselVorbei)
            verwalter.requestFlush(LocationManager.GPS_PROVIDER, this, WECHSEL_CODE)
            hand.postDelayed(wechselVorbei, NACHLAUF_MS)
            true
        } catch (e: Exception) {
            frueher = 0L
            logger.w { "Puffer beim Wechsel: " + e.message }
            false
        }
    }

    /**
     * Der Puffer des alten Trainings steht in seiner Datei (oder 3 s sind um). ERST
     * JETZT gilt das neue als [aufnahme]: eine spaete Zusammenfassung des alten
     * wartet in [stoppeUndWarte] sonst nicht auf seine letzte Minute.
     */
    private fun wechselFertig() {
        hand.removeCallbacks(wechselVorbei)
        if (frueher == 0L) return
        frueher = 0L
        if (laeuft) aufnahme.value = beginn
    }

    override fun onFlushComplete(requestCode: Int) {
        when (requestCode) {
            NACHLAUF_CODE -> if (haelt) beende()
            WECHSEL_CODE -> wechselFertig()
        }
    }

    /** Ortung ab, Meldung weg, Dienst aus - ohne auf den Puffer zu warten. */
    private fun beende() {
        hand.removeCallbacks(nachlaufVorbei)
        hand.removeCallbacks(wechselVorbei)
        frueher = 0L
        haelt = false
        if (laeuft) {
            try {
                getSystemService(LocationManager::class.java)?.removeUpdates(this)
            } catch (e: Exception) {
                // Abmelden wirft sonst nicht, auch nicht zweimal - ein Fehler hier ist unerwartet.
                logger.w { "Ortung nicht abgemeldet: " + e.message }
            }
            laeuft = false
        }
        aufnahme.value = 0L
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(ort: Location) {
        Spur.haengeAn(this, Spurwechsel.ziel(beginn, frueher), ort)
    }

    /** Ein Buendel des GPS: alle Punkte in einem Schreibvorgang (Spur.haengeAn). */
    override fun onLocationChanged(orte: List<Location>) {
        Spur.haengeAn(this, Spurwechsel.ziel(beginn, frueher), orte)
    }

    override fun onDestroy() {
        // Hier gibt es kein Warten mehr: was noch im Puffer liegt, geht verloren.
        beende()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun meldungskanal() {
        val kanal = NotificationChannel(
            KANAL, getString(R.string.ks_sp_kanal), NotificationManager.IMPORTANCE_LOW
        )
        kanal.description = getString(R.string.ks_sp_kanal_text)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(kanal)
    }

    companion object {
        private const val KANAL = "kiesel-spur"
        private const val MELDUNG_ID = 4711

        /** So lange darf das GPS seine Punkte sammeln: eine Minute, ~20 Punkte je Weckung. */
        private const val BUENDEL_MS = 60_000L

        /** So lange wartet ein Stopp hoechstens auf den Puffer; meist kommt er in Millisekunden. */
        private const val NACHLAUF_MS = 3_000L
        private const val NACHLAUF_CODE = 1
        private const val WECHSEL_CODE = 2

        /** Der Beginn des Trainings, das gerade aufgezeichnet wird, 0 ohne - fuer [stoppeUndWarte]. */
        private val aufnahme = MutableStateFlow(0L)
        private const val TAG = "Kieselsport"
        private val logger = Logger.withTag(TAG)
        private const val AUS = "coredevices.pebble.kiesel.SPUR_AUS"
        private const val EXTRA_BEGINN = "beginn"
        private const val EXTRA_ART = "art"

        /**
         * Darf überhaupt geortet werden?
         *
         * FEIN, NICHT GROB. Grob ist der Funkmast; daraus eine Laufstrecke zu
         * zeichnen wäre eine Behauptung, keine Messung.
         */
        fun darfOrten(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Darf auch geortet werden, wenn die App zu ist?
         *
         * DAS IST DER NORMALFALL UND NICHT DIE AUSNAHME: das Training beginnt
         * auf der Uhr, das Telefon liegt in der Tasche, die App ist zu. Ohne
         * "Immer erlauben" lässt Android einen Ortungsdienst in diesem Zustand
         * nicht zu — die Strecke bliebe leer, und niemand wüsste warum.
         */
        fun darfImmerOrten(context: Context): Boolean =
            Build.VERSION.SDK_INT < 29 ||
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun starte(context: Context, beginn: Long, art: String) {
            // OHNE ERLAUBNIS WIRD DER DIENST GAR NICHT ERST GESTARTET. Ein
            // Vordergrunddienst, der beim Start scheitert, reisst die App mit.
            if (!darfOrten(context)) {
                logger.w { "Keine Standortberechtigung - Spur entfällt" }
                KieselVerlauf.merke(context, context.getString(R.string.ks_sp_ohne_standort))
                return
            }
            val i = Intent(context, SpurDienst::class.java)
                .putExtra(EXTRA_BEGINN, beginn)
                .putExtra(EXTRA_ART, art)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                // Android 12 und neuer lassen einen Vordergrunddienst aus dem
                // Hintergrund heraus nicht in jedem Fall zu. Das Training wird
                // trotzdem eingetragen - nur ohne Strecke.
                logger.w { "Spurdienst nicht gestartet: " + e.message }
                KieselVerlauf.merke(
                    context, context.getString(R.string.ks_sp_ohne_strecke_x, e.message ?: e.javaClass.simpleName)
                )
            }
        }

        /**
         * Haelt die Spur an und wartet, bis ihr Puffer in der Datei steht. FUER
         * DIE ZUSAMMENFASSUNG: sie liest die Spur gleich danach.
         */
        suspend fun stoppeUndWarte(context: Context, beginn: Long) {
            stoppe(context, beginn)
            // 2 s ueber dem Nachlauf: der Dienst braucht selbst einen Moment bis zum Stopp.
            Spurwechsel.warteBisFrei(aufnahme, beginn, NACHLAUF_MS + 2_000)
        }

        /** Haelt die Spur an - mit [beginn] nur die dieses Trainings. */
        fun stoppe(context: Context, beginn: Long? = null) {
            val i = Intent(context, SpurDienst::class.java).setAction(AUS)
            beginn?.let { i.putExtra(EXTRA_BEGINN, it) }
            try {
                context.startService(i)
            } catch (e: Exception) {
                // Laeuft er nicht mehr, ist nichts zu stoppen.
                logger.i { "Spurdienst lief nicht: " + e.message }
            }
        }
    }
}

/**
 * Die Regeln des [SpurDienst] ohne Android - so prueft SpurwechselTest sie auf dem Rechner.
 */
internal object Spurwechsel {
    /**
     * In welche Datei ein Punkt geht: waehrend eines Wechsels ([frueher] > 0) noch
     * in die des alten Trainings - sein Puffer ist aelter als der Start des neuen.
     */
    fun ziel(beginn: Long, frueher: Long): Long = if (frueher > 0L) frueher else beginn

    /**
     * Wartet, bis [aufnahme] nicht mehr [beginn] ist (Puffer in der Datei, Dienst
     * aus oder schon beim naechsten), hoechstens [ms]. true, wenn es so kam.
     */
    suspend fun warteBisFrei(aufnahme: StateFlow<Long>, beginn: Long, ms: Long): Boolean =
        withTimeoutOrNull(ms) { aufnahme.first { it != beginn } } != null
}
