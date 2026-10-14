package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import coredevices.pebble.health.PlatformHealthSync
import coredevices.pebble.health.fehlendeAkteRechte
import org.koin.mp.KoinPlatform

/**
 * Beim Start alle Rechte der Akte in einem Dialog erfragen, statt sie
 * einzeln dort, wo sie fehlen (Einstellungen, Trainingsansicht). Boulder ist
 * eine persoenliche App - es gibt niemanden, dem man jedes Recht erst
 * erklaeren muesste.
 *
 * EINMAL JE FASSUNG (versionName, also BOULDER_VERSION): wer ein Recht
 * absichtlich verweigert, soll nicht bei jedem Oeffnen denselben Dialog sehen.
 * Bringt ein Update ein neues Recht, fragt die neue Fassung wieder. Ueber den
 * Sync, damit er mit den Rechten auch gleich eingeschaltet ist - ausschalten
 * kann ihn dieser Dialog nicht.
 */
internal object Rechtefrage {
    private const val TAG = "Rechtefrage"
    private val logger = Logger.withTag(TAG)
    // Ein neuer Schluessel: unter dem alten lag der versionCode als Long, und
    // getString auf einem Long wirft.
    private const val GEFRAGT = "akte_rechte_gefragt_fassung"
    private const val GEFRAGT_ALT = "akte_rechte_gefragt"

    suspend fun beimStart(context: Context) {
        // Der versionCode zaehlt Commits, also jeden Testbuild - gemeint ist die Fassung.
        val fassung = versuche(TAG, "Fassung lesen") { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.orEmpty()
        val prefs = context.getSharedPreferences("boulder_rechtefrage", Context.MODE_PRIVATE)
        val gefragt = prefs.getString(GEFRAGT, null)
        // Zuerst die gemerkte Fassung: spart bei jedem weiteren Start den Aufruf in die Akte.
        if (schonGefragt(gefragt, fassung)) return
        val fehlend = try {
            // Ohne Akte gibt es nichts zu fragen - und nichts, woran der Start scheitern koennte.
            val klient = Akte(context).bereit() ?: return
            fehlendeAkteRechte(klient)
        } catch (e: Exception) {
            logger.w { "Rechte lesen: " + e.message }
            return
        }
        if (!sollFragen(fehlend, gefragt, fassung)) return
        // Vor dem Dialog gemerkt, nicht danach: stirbt die App waehrend des
        // Dialogs, fragte sonst jeder Start wieder - eine Schleife ohne Ausweg.
        prefs.edit().putString(GEFRAGT, fassung).remove(GEFRAGT_ALT).apply()
        logger.i { "Frage ${fehlend.size} fehlende Rechte: $fehlend" }
        KoinPlatform.getKoin().getOrNull<PlatformHealthSync>()?.requestPermissions(nurEinschalten = true)
    }

    /** Fragen, wenn etwas fehlt und diese Fassung noch nicht gefragt hat. */
    internal fun sollFragen(fehlend: Set<String>, gefragt: String?, fassung: String): Boolean =
        fehlend.isNotEmpty() && !schonGefragt(gefragt, fassung)

    internal fun schonGefragt(gefragt: String?, fassung: String): Boolean = gefragt == fassung
}
