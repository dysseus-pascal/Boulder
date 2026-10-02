package coredevices.pebble.kiesel

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
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
                // Boulder: jedes Zeichnen liest Datenbank und Akte ganz. Kommen
                // Anstoesse dicht nacheinander, zeichnet der laufende Durchgang
                // einmal nach, statt dass mehrere parallel dasselbe lesen.
                val ausgang = zeichner.lauf {
                    val stand = Gesundheitsdaten.lies(context, KoinPlatform.getKoin().get<HealthDataApi>())
                    val sicht = zeichne(context, stand, blick(context, stand))
                    val alle = manager.getAppWidgetIds(ComponentName(context, GesundheitWidget::class.java)) ?: intArrayOf()
                    (ids.toSet() + alle.toSet()).forEach { manager.updateAppWidget(it, sicht) }
                }
                // Die Nachlaeufe sind begrenzt, damit dieses goAsync nicht ueber seine
                // Frist waechst; was offen blieb, bekommt einen eigenen Broadcast.
                if (ausgang == Einzellauf.Ausgang.OFFEN) stosseAn(context)
            } catch (e: Exception) {
                Log.w("Widget", "Zeichnen: " + e.message)
            } finally {
                fertig.finish()
            }
        }
    }

    companion object {
        private val zeichner = Einzellauf(hoechstens = 2, beiFehler = { Log.w("Widget", "Zeichnen: " + it.message) })
        private const val DATEI = "kiesel-widget"
        private const val WUNSCH = "wunsch"
        private const val WUNSCH_BIS = "wunsch_bis"
        private const val GELESEN = "gelesen"
        private const val EXTRA_LAGE = "lage"

        /** Neu zeichnen - nach neuen Daten von der Uhr und nach eigenen Eintraegen. */
        fun stosseAn(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, GesundheitWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            context.sendBroadcast(Intent(context, GesundheitWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }

        private suspend fun blick(context: Context, s: GesundheitsStand): Widgetlage.Blick {
            val zone = ZoneId.systemDefault()
            val heute = LocalDate.now()
            val n = s.nacht
            // DASSELBE TRAINING WIE OBEN AUF DER SPORTSEITE, mit demselben Puls
            // (entstoert, nur in der Sitzung, ohne Pausen) und demselben Namen.
            // Frueher war es die Sitzung, die zuletzt endete - auch die einer
            // fremden App neben der eigenen -, mit rohem Puls aller Quellen.
            var training: TrainingEintrag? = null
            var puls: Double? = null
            try {
                training = TrainingsDaten.juengstes(context, Duration.ofDays(1))
                    ?.takeIf { Duration.between(it.sitzung.endTime, Instant.now()).toHours() < 3 }
                puls = training?.let { TrainingsDaten.mittelpuls(context, it.sitzung) }?.toDouble()
            } catch (e: Exception) {
                Log.w("Widget", "Training: " + e.message)
            }
            val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
            val wunsch = if (p.getLong(WUNSCH_BIS, 0) > System.currentTimeMillis()) {
                p.getString(WUNSCH, null)?.let { runCatching { Widgetlage.Art.valueOf(it) }.getOrNull() }
            } else null
            val woche = s.wocheSchlaf.filter { it.first != heute && it.second > 0 }.map { it.second.toDouble() }
            val platz = context.getString(R.string.ks_s_platz_n)
            return Widgetlage.Blick(
                jetzt = LocalDateTime.now(zone),
                schlafEnde = n?.aufwachen?.atZone(zone)?.toLocalDateTime(),
                schlafMin = n?.schlafMinuten?.toDouble(),
                schlafZielMin = 480.0,
                schlafWocheMin = woche.takeIf { it.isNotEmpty() }?.average(),
                erholsamAnteil = n?.takeIf { it.mitPhasen }?.let { (it.phasen[2] + it.phasen[3]).toDouble() / it.phasen.sum().coerceAtLeast(1) },
                ruhepuls = n?.ruhepuls?.toDouble(),
                hrv = n?.hrv?.toDouble(),
                trainingEnde = training?.sitzung?.endTime?.atZone(zone)?.toLocalDateTime(),
                trainingName = training?.let { TrainingsDaten.name(context, it.sitzung) },
                trainingMin = training?.minuten,
                trainingPuls = puls,
                trainingKm = training?.meter?.takeIf { it > 0 }?.div(1000),
                trainingBeginn = training?.sitzung?.startTime?.epochSecond,
                schritte = s.schritte.toDouble(),
                schritteZiel = 10_000.0,
                aktivMin = s.aktivMinuten.toDouble(),
                aktivZiel = 30.0,
                // Ohne Drinktervall keine Wasserkachel und keine Mahnung (UhrApps).
                wasserMl = s.wasserMl.toDouble().takeIf { s.mitDrinktervall },
                wasserZiel = s.wasserZiel?.ml?.toDouble()?.takeIf { s.mitDrinktervall },
                glasMl = (s.wasserZiel?.glasMl ?: 300).toDouble(),
                praeparate = s.supplemente?.takeIf { s.mitSupCycle }?.heute { String.format(platz, it) }.orEmpty(),
                koffeinMg = Gesundheitsrechnung.koffeinHeute(s.koffein, heute, zone).takeIf { s.mitDrinktervall },
                wunsch = wunsch,
            )
        }

        private fun zeichne(context: Context, s: GesundheitsStand, blick: Widgetlage.Blick): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.ks_widget_gesundheit)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { start ->
                v.setOnClickPendingIntent(R.id.w_wurzel, PendingIntent.getActivity(context, 0, start, PendingIntent.FLAG_IMMUTABLE))
            }
            // Android laesst ein Widget nur alle halbe Stunde von selbst nachsehen; der Knopf holt es sofort.
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
            context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putLong(GELESEN, System.currentTimeMillis()).apply()
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
                v.setTextViewText(wert, k.text ?: "—")
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
                s.nacht?.ruhepuls?.let { context.getString(R.string.ks_w_ruhepuls_wert, it.toString()) },
                s.nacht?.hrv?.let { "HRV $it ms" },
                s.meter.takeIf { it >= 100 }?.let { Zahlen.eine(it / 1000) + " km" },
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
