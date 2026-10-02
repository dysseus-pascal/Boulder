package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import coredevices.pebble.health.PlatformHealthSync
import coredevices.pebble.health.alleAkteRechte
import org.koin.mp.KoinPlatform

/**
 * Beim Start alle Rechte der Akte in einem Dialog erfragen, statt sie
 * einzeln dort, wo sie fehlen (Einstellungen, Trainingsansicht). Boulder ist
 * eine persoenliche App - es gibt niemanden, dem man jedes Recht erst
 * erklaeren muesste.
 *
 * EINMAL JE FASSUNG: wer ein Recht absichtlich verweigert, soll nicht bei
 * jedem Oeffnen denselben Dialog sehen. Bringt ein Update ein neues Recht,
 * fragt die neue Fassung wieder. Ueber den Sync, damit er mit den Rechten
 * auch gleich eingeschaltet ist.
 */
internal object Rechtefrage {
    private const val TAG = "Rechtefrage"
    private const val GEFRAGT = "akte_rechte_gefragt"

    suspend fun beimStart(context: Context) {
        val klient = Akte(context).bereit() ?: return
        val fehlend = try {
            alleAkteRechte() - klient.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "Rechte lesen: " + e.message)
            return
        }
        if (fehlend.isEmpty()) return
        val fassung = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        val prefs = context.getSharedPreferences("boulder_rechtefrage", Context.MODE_PRIVATE)
        if (prefs.getLong(GEFRAGT, -1L) == fassung) return
        prefs.edit().putLong(GEFRAGT, fassung).apply()
        Log.i(TAG, "Frage ${fehlend.size} fehlende Rechte: $fehlend")
        KoinPlatform.getKoin().getOrNull<PlatformHealthSync>()?.requestPermissions()
    }
}
