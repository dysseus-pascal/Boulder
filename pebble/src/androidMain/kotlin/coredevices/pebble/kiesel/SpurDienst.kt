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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

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
            Log.w(TAG, "Keine Standortberechtigung - Spur entfällt")
            stopSelf()
            return START_NOT_STICKY
        }

        beginn = intent?.getLongExtra(EXTRA_BEGINN, 0L) ?: 0L
        if (beginn <= 0L) beginn = System.currentTimeMillis() / 1000
        if (!starteVordergrund(intent?.getStringExtra(EXTRA_ART) ?: getString(R.string.ks_training))) {
            stopSelf()
            return START_NOT_STICKY
        }
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
            Log.w(TAG, "Vordergrunddienst: " + e.message)
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
            verwalter.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, takt, abstand, this
            )
            laeuft = true
        } catch (e: Exception) {
            Log.w(TAG, "Ortung: " + e.message)
            halt()
        }
    }

    private fun halt() {
        if (laeuft) {
            try {
                getSystemService(LocationManager::class.java)?.removeUpdates(this)
            } catch (e: Exception) {
                // schon abgemeldet - nichts zu tun
            }
            laeuft = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(ort: Location) {
        Spur.haengeAn(this, beginn, ort)
    }

    override fun onDestroy() {
        halt()
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
        private const val TAG = "Kieselsport"
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
                Log.w(TAG, "Keine Standortberechtigung - Spur entfällt")
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
                Log.w(TAG, "Spurdienst nicht gestartet: " + e.message)
                KieselVerlauf.merke(
                    context, context.getString(R.string.ks_sp_ohne_strecke_x, e.message ?: e.javaClass.simpleName)
                )
            }
        }

        /** Haelt die Spur an - mit [beginn] nur die dieses Trainings. */
        fun stoppe(context: Context, beginn: Long? = null) {
            val i = Intent(context, SpurDienst::class.java).setAction(AUS)
            beginn?.let { i.putExtra(EXTRA_BEGINN, it) }
            try {
                context.startService(i)
            } catch (e: Exception) {
                // Laeuft er nicht mehr, ist nichts zu stoppen.
                Log.i(TAG, "Spurdienst lief nicht: " + e.message)
            }
        }
    }
}
