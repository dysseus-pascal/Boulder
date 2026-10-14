package coredevices.pebble.kiesel

import android.content.Context
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.health.BoulderZiele
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Die Ziele und Grenzwerte, an denen Seiten, Trends, Vergleiche und Widget
 * messen. AN EINEM ORT, weil jede Stelle dasselbe Ziel meint: stand das
 * Schrittziel viermal im Code, haette eine Aenderung an einer Stelle drei
 * verschiedene Ziele auf den Schirm gebracht.
 *
 * Schritte und Schlaf stellt man unter "Goals" ein ([ziele], [setze]); sie
 * gelten auch auf der Uhr mit Boulder-Firmware V2 ([BoulderZiele]). Die
 * uebrigen sind fest; die Uhr kennt sie nicht (ausser dem Wasser, siehe
 * [Drinktervall]).
 */
internal object Gesundheitsziele {
    /** Schritte am Tag und Schlaf je Nacht in Minuten; ohne Einstellung dieselbe Vorgabe wie auf der Uhr. */
    data class Ziele(
        val schritte: Int = BoulderZiele.SCHRITTE_VORGABE,
        val schlafMin: Int = BoulderZiele.SCHLAF_VORGABE,
    ) {
        /** Eine Stunde unter dem Schlafziel beginnt die "very short night" - auf Health und in den Vergleichen. */
        val sehrKurzMin: Int get() = schlafMin - 60
    }

    /** Aktive Minuten am Tag: die halbe Stunde der WHO - Widgetkachel und der "active day" der Vergleiche. */
    const val AKTIV_MIN = 30

    /** Weniger Wachminuten in einer Nacht gelten als durchgeschlafen - kurzes Aufwachen merkt niemand. */
    const val DURCHGESCHLAFEN_MIN = 10

    /** Glaeser Wasser am Tag, solange Drinktervall kein eigenes Ziel gemeldet hat - die Vorgabe der Uhr-App. */
    const val WASSER_GLAESER = 8

    /** Milliliter je Glas, ebenso die Vorgabe von Drinktervall. */
    const val GLAS_ML = 300

    private const val DATEI = "boulder-ziele"
    private const val SCHRITTE = "schritte"
    private const val SCHLAF = "schlaf_min"

    // Im eigenen Bereich, nicht in dem der Seite: wer gleich zurueck tippt, verliert die letzte
    // Aenderung nicht. Die Sperre laesst immer nur einen Schreiber an die Datenbank.
    private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sperre = Mutex()

    /** Die eingestellten Ziele. Ein Wert ausserhalb der Grenzen der Uhr kommt nicht vor; gelesen wird trotzdem nur innerhalb. */
    fun ziele(context: Context): Ziele {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        return Ziele(
            schritte = p.getInt(SCHRITTE, BoulderZiele.SCHRITTE_VORGABE)
                .coerceIn(BoulderZiele.SCHRITTE_MIN, BoulderZiele.SCHRITTE_MAX),
            schlafMin = p.getInt(SCHLAF, BoulderZiele.SCHLAF_VORGABE)
                .coerceIn(BoulderZiele.SCHLAF_MIN, BoulderZiele.SCHLAF_MAX),
        )
    }

    /**
     * Neue Ziele: sofort gemerkt - Seiten und Widget lesen sie gleich -, dann zur
     * Uhr ueber die Datenbank Health, auch spaeter, wenn sie jetzt nicht verbunden ist.
     */
    fun setze(context: Context, api: HealthDataApi, neu: Ziele): Job {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
            .putInt(SCHRITTE, neu.schritte).putInt(SCHLAF, neu.schlafMin).apply()
        GesundheitWidget.stosseAn(context)
        return zurUhr(context.applicationContext, api)
    }

    /**
     * Beim Start der App die gemerkten Ziele noch einmal in die Datenbank Health:
     * fehlt dort die Zeile (eine neue Datenbank nach einem Rueckweg), bekaeme die
     * Uhr bei der naechsten Kopplung die Vorgabe. Derselbe Wert geht nicht erneut
     * zur Uhr. Nie eingestellt: nichts - dann gilt dort ohnehin die Vorgabe.
     */
    fun beimStart(context: Context, api: HealthDataApi): Job? {
        if (!context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).contains(SCHRITTE)) return null
        return zurUhr(context.applicationContext, api)
    }

    // DER GEMERKTE STAND, nicht der des Aufrufs: schnelle Taps laufen hier in beliebiger
    // Reihenfolge an, und so steht am Ende doch die juengste Aenderung in der Datenbank.
    private fun zurUhr(context: Context, api: HealthDataApi): Job = bereich.launch {
        sperre.withLock {
            val z = ziele(context)
            versuche("Ziele", "nicht in die Datenbank Health") { api.setzeZiele(z.schritte, z.schlafMin) }
        }
    }
}
