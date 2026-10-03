package coredevices.pebble.kiesel

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import coredevices.pebble.R
import coredevices.pebble.health.Einzellauf
import coredevices.pebble.kiesel.ansicht.einheitenImperialHolen
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Das Gesundheits-Widget - wie in Kiesel-Helper: eine Buehne mit dem, was
 * gerade zaehlt (die Nacht am Morgen, das Training danach, eine Erinnerung,
 * die Bilanz am Abend, sonst die Schritte), darunter drei kleine Kacheln.
 * Ein Tipp auf eine Kachel macht sie fuer eine Stunde zur Buehne.
 */
class GesundheitWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val fertig = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Erst das Netz in 30 min, dann lesen: stirbt der Prozess dabei, steht der Takt trotzdem.
                val ausgang = Widgetlage.mitTakt(Instant.now(), { plane(context, it) }) {
                    var wende: Instant? = null
                    // Boulder: Kommen Anstoesse dicht nacheinander, zeichnet der laufende
                    // Durchgang einmal nach, statt dass mehrere parallel dasselbe lesen.
                    val ausgang = zeichner.lauf {
                        // Nur was das Widget zeigt: vorher las jedes Zeichnen den ganzen
                        // Stand der Seite samt 14 Tagen Minutenpuls (~100-150 Mal am Tag).
                        val stand = Gesundheitsdaten.liesFuerWidget(context, KoinPlatform.getKoin().get<HealthDataApi>())
                        val (blick, wunschBis) = blick(context, stand)
                        val sicht = zeichne(context, stand, blick)
                        val alle = manager.getAppWidgetIds(ComponentName(context, GesundheitWidget::class.java)) ?: intArrayOf()
                        (ids.toSet() + alle.toSet()).forEach { manager.updateAppWidget(it, sicht) }
                        wende = Widgetlage.naechsteWende(blick, wunschBis).atZone(ZoneId.systemDefault()).toInstant()
                    }
                    ausgang to wende
                }
                // Die Nachlaeufe sind begrenzt, damit dieses goAsync nicht ueber seine
                // Frist waechst; was offen blieb, bekommt einen eigenen Broadcast. Nur
                // zeichnen: wer den offenen Anstoss gab, hat die Seiten schon veraltet.
                if (ausgang == Einzellauf.Ausgang.OFFEN) zeichneNeu(context)
            } catch (e: Exception) {
                Log.w("Widget", "Zeichnen: " + e.message)
            } finally {
                fertig.finish()
            }
        }
    }

    // Das letzte Widget ist weg: kein Takt mehr, der nichts zu zeichnen hat.
    override fun onDisabled(context: Context) {
        taktAbsicht(context, anlegen = false)?.let { pi ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
    }

    companion object {
        private val zeichner = Einzellauf(hoechstens = 2, beiFehler = { Log.w("Widget", "Zeichnen: " + it.message) })
        private const val DATEI = "kiesel-widget"
        private const val WUNSCH = "wunsch"
        private const val WUNSCH_BIS = "wunsch_bis"
        private const val EXTRA_LAGE = "lage"

        /**
         * Wie weit Android den Takt schieben darf. Kuerzer als 10 min dehnt
         * Android ein ungenaues Fenster ohnehin auf 10 min (ab Android 12);
         * frueher kam der Systemtakt bis zu 30 min nach einer Wende.
         */
        private const val FENSTER_MS = 10 * 60 * 1000L

        /** Neu zeichnen - nach neuen Daten von der Uhr und nach eigenen Eintraegen. */
        fun stosseAn(context: Context) {
            // Neue Daten oder ein eigener Eintrag: was die Seiten eben gelesen haben, ist nicht mehr frisch.
            Gesundheitsdaten.veraltet()
            zeichneNeu(context)
        }

        /** Der Broadcast an alle platzierten Widgets; ohne Widget keiner. */
        private fun zeichneNeu(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, GesundheitWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            context.sendBroadcast(Intent(context, GesundheitWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }

        private val taktGesichert = AtomicBoolean(false)

        /**
         * Einmal je Prozess beim Oeffnen der App (PebbleAndroidDelegate):
         * zeichnen und damit den Takt neu planen. Nach "Beenden erzwingen"
         * loescht Android Alarm und Absicht; ohne Uhr kaeme dann kein Anstoss
         * mehr. OHNE ZU PRUEFEN, OB DER TAKT NOCH STEHT: eine bestehende
         * Absicht sagt nichts ueber einen anstehenden Alarm - sie ueberlebt den
         * ausgeloesten Alarm und den Prozesstod. Das kostet ein Zeichnen je
         * neuem Prozess der App, also ein paar am Tag (der Dienst haelt ihn wach).
         */
        suspend fun sichereTakt(context: Context) {
            if (!taktGesichert.compareAndSet(false, true)) return
            // Zwei Anfragen an Android - nicht auf dem Hauptfaden. Keine neuen Daten: der Stand der Seiten bleibt frisch.
            withContext(Dispatchers.IO) { zeichneNeu(context) }
        }

        /**
         * Der Takt fuer das naechste Zeichnen ohne neue Daten. RTC, NICHT
         * RTC_WAKEUP: er weckt das Telefon nicht - schlaeft es, kommt er beim
         * naechsten Aufwachen, und erst dann sieht man das Widget ja auch.
         * Frueher weckte Androids updatePeriodMillis das Telefon 48-mal am Tag.
         * Jedes Zeichnen plant neu; mit Uhr schiebt so jeder Schub (alle 15 min)
         * den Takt weiter, und es bleibt bei den Wenden der Lage.
         */
        private fun plane(context: Context, um: Instant) {
            // Ein Fehler hier darf das Zeichnen nicht verhindern - es plant danach noch einmal.
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, GesundheitWidget::class.java))
                if (ids == null || ids.isEmpty()) return
                val pi = taktAbsicht(context, anlegen = true, ids) ?: return
                context.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, um.toEpochMilli(), FENSTER_MS, pi)
            } catch (e: RuntimeException) {
                Log.w("Widget", "Takt: " + e.message)
            }
        }

        /** Die Absicht des Takts; ohne [anlegen] nur, wenn sie schon besteht (sonst null). */
        private fun taktAbsicht(context: Context, anlegen: Boolean, ids: IntArray? = null): PendingIntent? =
            PendingIntent.getBroadcast(
                context, 2,
                Intent(context, GesundheitWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    ids?.let { putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, it) }
                },
                PendingIntent.FLAG_IMMUTABLE or if (anlegen) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
            )

        /** Der Blick fuer die Lage, und bis wann ein Kachelwunsch gilt (fuer den Takt). */
        private suspend fun blick(context: Context, s: GesundheitsStand): Pair<Widgetlage.Blick, LocalDateTime?> {
            val zone = ZoneId.systemDefault()
            // DASSELBE TRAINING WIE OBEN AUF DER SPORTSEITE, mit demselben Puls
            // (entstoert, nur in der Sitzung, ohne Pausen) und demselben Namen.
            // Frueher war es die Sitzung, die zuletzt endete - auch die einer
            // fremden App neben der eigenen -, mit rohem Puls aller Quellen.
            var training: Widgetlage.Training? = null
            try {
                training = TrainingsDaten.juengstes(context, Duration.ofDays(1))
                    ?.takeIf { Duration.between(it.sitzung.endTime, Instant.now()).toHours() < 3 }
                    ?.let { t ->
                        Widgetlage.Training(
                            ende = t.sitzung.endTime.atZone(zone).toLocalDateTime(),
                            name = TrainingsDaten.name(context, t.sitzung),
                            minuten = t.minuten,
                            puls = TrainingsDaten.mittelpuls(context, t.sitzung)?.toDouble(),
                            km = t.meter.takeIf { it > 0 }?.div(1000),
                            beginn = t.sitzung.startTime.epochSecond,
                            metrisch = t.sport.immerMetrisch,
                        )
                    }
            } catch (e: Exception) {
                Log.w("Widget", "Training: " + e.message)
            }
            val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
            val bis = p.getLong(WUNSCH_BIS, 0)
            val wunsch = if (bis > System.currentTimeMillis()) {
                p.getString(WUNSCH, null)?.let { runCatching { Widgetlage.Art.valueOf(it) }.getOrNull() }
            } else null
            val platz = context.getString(R.string.ks_s_platz_n)
            // Gravels "Imperial Units" auch hier: das Widget zeigte sonst km neben einer App in mi.
            val blick = Widgetlage.blick(s, LocalDateTime.now(zone), zone, training, wunsch, einheitenImperialHolen()) { String.format(platz, it) }
            val wunschBis = if (wunsch != null) Instant.ofEpochMilli(bis).atZone(zone).toLocalDateTime() else null
            return blick to wunschBis
        }

        private fun zeichne(context: Context, s: GesundheitsStand, blick: Widgetlage.Blick): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.ks_widget_gesundheit)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { start ->
                v.setOnClickPendingIntent(R.id.w_wurzel, PendingIntent.getActivity(context, 0, start, PendingIntent.FLAG_IMMUTABLE))
            }
            // Ohne neue Daten zeichnet das Widget erst zur naechsten Wende oder nach einer halben Stunde; der Knopf holt es sofort.
            v.setOnClickPendingIntent(
                R.id.w_auffrischen,
                PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, GesundheitWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, GesundheitWidget::class.java)))
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setTextViewText(R.id.w_stand, context.getString(R.string.ks_w_gerade_eben))

            val texte = object : Texte {
                override fun text(id: Int, vararg a: Any) = context.getString(id, *a)
                override fun mehrzahl(id: Int, n: Int, vararg a: Any) = context.resources.getQuantityString(id, n, *a)
            }
            val lage = Widgetlage.ermittle(blick, texte)
            v.setTextViewText(R.id.w_lage, lage.wort)
            v.setTextViewText(R.id.w_name, lage.name)
            v.setTextViewText(R.id.w_gross, lage.gross)
            v.setTextViewText(R.id.w_einheit, lage.einheit)
            v.setTextViewText(R.id.w_satz, lage.satz)
            if (lage.anteil != null) {
                v.setViewVisibility(R.id.w_balken, View.VISIBLE)
                v.setProgressBar(R.id.w_balken, 100, (lage.anteil * 100).toInt(), false)
            } else {
                v.setViewVisibility(R.id.w_balken, View.GONE)
            }
            val felder = listOf(
                listOf(R.id.w_k1, R.id.w_k1_name, R.id.w_k1_wert, R.id.w_k1_balken),
                listOf(R.id.w_k2, R.id.w_k2_name, R.id.w_k2_wert, R.id.w_k2_balken),
                listOf(R.id.w_k3, R.id.w_k3_name, R.id.w_k3_wert, R.id.w_k3_balken),
            )
            // Fehlt eine Kachel (Wasser ohne Drinktervall), bleibt ihr Platz leer.
            felder.forEachIndexed { i, f -> v.setViewVisibility(f[0], if (i < lage.kacheln.size) View.VISIBLE else View.GONE) }
            lage.kacheln.take(3).forEachIndexed { i, k ->
                val (wurzel, name, wert, balken) = felder[i]
                v.setTextViewText(name, k.name)
                v.setTextViewText(wert, k.text ?: Formate.FEHLT)
                v.setProgressBar(balken, 100, (k.anteil * 100).toInt(), false)
                val wunsch = when (k.schluessel) {
                    "schlaf" -> Widgetlage.Art.MORGEN
                    "wasser" -> Widgetlage.Art.ERINNERUNG
                    else -> Widgetlage.Art.TAG
                }
                v.setOnClickPendingIntent(
                    wurzel,
                    PendingIntent.getBroadcast(
                        context, 10 + i,
                        Intent(context, Wunsch::class.java).putExtra(EXTRA_LAGE, wunsch.name),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            val fuss = listOfNotNull(
                s.nacht?.ruhepuls?.let { context.getString(R.string.ks_w_ruhepuls_wert, Formate.zahl(it.toLong())) },
                s.nacht?.hrv?.let { "HRV " + Formate.menge(it.toLong(), "ms").text },
                s.meter.takeIf { it >= 100 }?.let { Formate.distanz(it, blick.imperial).text },
            )
            v.setTextViewText(R.id.w_fuss, fuss.ifEmpty { listOf(context.getString(R.string.ks_w_nichts)) }.joinToString(" · "))
            return v
        }
    }
}

/**
 * Ein Tipp auf eine Kachel. Ein eigener, nicht exportierter Empfaenger: das
 * Widget selbst muss fuer den Launcher offen sein, diese Aktion soll nur die
 * App ausloesen koennen.
 */
class Wunsch : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("kiesel-widget", Context.MODE_PRIVATE).edit()
            .putString("wunsch", intent.getStringExtra("lage"))
            .putLong("wunsch_bis", System.currentTimeMillis() + 60 * 60 * 1000)
            .apply()
        GesundheitWidget.stosseAn(context)
    }
}
