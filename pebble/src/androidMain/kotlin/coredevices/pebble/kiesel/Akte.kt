package coredevices.pebble.kiesel

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/**
 * Die Gesundheitsakte (Health Connect), wenn es sie gibt.
 *
 * NULL HEISST: auf diesem Telefon nicht da oder nicht bereit. Wer schreibt,
 * sagt es dann im Protokoll, statt zu scheitern. Ob die Erlaubnis fehlt,
 * zeigt erst das Schreiben selbst - die Akte weist dann ab.
 */
internal class Akte(private val context: Context) {
    fun bereit(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
}
