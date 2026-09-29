package coredevices.pebble.kiesel

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.reflect.KClass

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

/**
 * Lesen - und im Hintergrund nur das Eigene. Ohne das Recht, im Hintergrund zu
 * lesen, weist Health Connect jede Abfrage ab, die fremde Eintraege treffen
 * koennte; das Widget liest aber im Hintergrund. Beim zweiten Versuch fragt es
 * nur nach Boulders eigenen Eintraegen - Drinktervall, Koffein, Trainings.
 */
internal suspend fun <T : Record> HealthConnectClient.liesOderEigene(context: Context, klasse: KClass<T>, zeit: TimeRangeFilter): List<T> = try {
    readRecords(ReadRecordsRequest(klasse, zeit)).records
} catch (e: Exception) {
    readRecords(ReadRecordsRequest(klasse, zeit, dataOriginFilter = setOf(DataOrigin(context.packageName)))).records
}
