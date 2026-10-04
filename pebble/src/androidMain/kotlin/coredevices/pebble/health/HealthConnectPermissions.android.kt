package coredevices.pebble.health

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.viktormykhailiv.kmp.health.HealthDataType
import com.viktormykhailiv.kmp.health.HealthManager
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.KIESEL_RECHTE
import coredevices.pebble.kiesel.nurVerfuegbare
import coredevices.pebble.kiesel.versuche
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred

/**
 * Requests every Health Connect write permission the app needs in a single system prompt.
 *
 * health-kmp 1.4.0 models its own type set and launches a separate permission activity for it; it
 * also doesn't model OxygenSaturation, which previously forced a second prompt just for SpO2. To
 * keep the UX to one prompt, this bypasses health-kmp's [requestAuthorization] on Android and asks
 * for the standard permissions together with SpO2 via a single permission contract. SpO2 is treated
 * as best-effort: the result reflects only whether the standard types were granted, so denying SpO2
 * doesn't disable sync for steps/heart rate/sleep/exercise.
 */
internal actual suspend fun requestPlatformHealthPermissions(
    healthManager: HealthManager,
    readTypes: List<HealthDataType>,
    writeTypes: List<HealthDataType>,
): Boolean {
    if (!healthManager.isAvailable().getOrDefault(false)) return false
    val ctx = context()
    val klient = Akte(ctx).bereit() ?: return false
    val standardPerms = standardRechte(readTypes, writeTypes)
    // Boulder: was Kieselsport braucht, im selben Dialog - dieselbe Menge wie
    // aus Einstellungen und Sportansicht. Wie SpO2 nur nach Moeglichkeit: ohne
    // sie laeuft der Sync der anderen Daten trotzdem.
    val granted = HealthConnectPermissionBridge.request(ctx, standardPerms + alleAkteRechte(klient))
    return standardPerms.all { it in granted }
}

private fun standardRechte(readTypes: List<HealthDataType>, writeTypes: List<HealthDataType>): Set<String> =
    writeTypes.flatMapTo(mutableSetOf()) { it.toHealthConnectWritePermissions() } +
        readTypes.flatMapTo(mutableSetOf()) { it.toHealthConnectReadPermissions() }

/**
 * Boulder: jedes Recht, das die App in dieser Akte haben kann - der Inhalt des
 * einen Dialogs und zugleich, was Einstellungen und Sportansicht als fehlend
 * zaehlen. Eine Stelle, damit Zahl und Dialog nicht auseinanderlaufen.
 */
internal fun alleAkteRechte(klient: HealthConnectClient): Set<String> =
    nurVerfuegbare(alleRechteJederAkte()) { funktion ->
        // Kann die Akte nicht sagen, was sie kann, fehlt ihr die Funktion - lieber
        // ein Recht nicht fragen als es fuer immer als fehlend zaehlen.
        versuche("HealthConnect", "Funktion $funktion nicht abfragbar, ihr Recht wird nicht gefragt") {
            klient.features.getFeatureStatus(funktion) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } ?: false
    }

/** Boulder: dieselben Rechte vor dem Abgleich mit der Akte - jedes muss im Manifest stehen (RechteTest). */
internal fun alleRechteJederAkte(): Set<String> =
    standardRechte(PlatformHealthSync.RequestedReadTypes, PlatformHealthSync.RequestedWriteTypes) +
        WRITE_OXYGEN_SATURATION + KIESEL_RECHTE

/** Boulder: was davon noch nicht erteilt ist. */
internal suspend fun fehlendeAkteRechte(klient: HealthConnectClient): Set<String> =
    alleAkteRechte(klient) - klient.permissionController.getGrantedPermissions()

/**
 * Boulder: der eine Dialog aus Einstellungen und Sportansicht. Am Sync aendert
 * er nichts; ohne Akte gibt es nichts zu fragen.
 */
internal suspend fun frageAlleAkteRechte(context: Context) {
    val klient = Akte(context).bereit() ?: return
    HealthConnectPermissionBridge.request(context, alleAkteRechte(klient))
}

private const val KEY_PERMISSIONS = "KEY_PERMISSIONS"
private const val KEY_ANFRAGE = "KEY_ANFRAGE"

private fun HealthDataType.toHealthConnectWritePermissions(): Set<String> = when (this) {
    HealthDataType.Steps -> setOf(writePermission(StepsRecord::class))
    HealthDataType.HeartRate -> setOf(writePermission(HeartRateRecord::class))
    HealthDataType.Sleep -> setOf(writePermission(SleepSessionRecord::class))
    is HealthDataType.Exercise -> setOf(
        writePermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )
    else -> emptySet()
}

private fun HealthDataType.toHealthConnectReadPermissions(): Set<String> = when (this) {
    HealthDataType.Steps -> setOf(readPermission(StepsRecord::class))
    HealthDataType.HeartRate -> setOf(readPermission(HeartRateRecord::class))
    HealthDataType.Sleep -> setOf(readPermission(SleepSessionRecord::class))
    is HealthDataType.Exercise -> setOf(readPermission(ExerciseSessionRecord::class))
    else -> emptySet()
}

private fun writePermission(recordType: KClass<out Record>): String =
    HealthPermission.getWritePermission(recordType)

private fun readPermission(recordType: KClass<out Record>): String =
    HealthPermission.getReadPermission(recordType)

internal object HealthConnectPermissionBridge {
    private val anfragen = Anfragen<Set<String>>()

    /**
     * Returns the granted subset. Throws CancellationException when a newer request supersedes
     * this one: that is no answer, and callers must not read it as a denial.
     */
    suspend fun request(context: Context, permissions: Set<String>): Set<String> {
        val (nummer, antwort) = anfragen.neu()
        try {
            context.startActivity(
                Intent(context, HealthConnectWritePermissionActivity::class.java)
                    .putExtra(KEY_PERMISSIONS, permissions.toTypedArray())
                    .putExtra(KEY_ANFRAGE, nummer)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            logger.w(e) { "Failed launching Health Connect permission request" }
            anfragen.beantworte(nummer, null)
        }
        // Boulder: await steht ausserhalb des catch. Frueher fing catch (Exception)
        // die CancellationException der abgeloesten Anfrage, beantwortete mit
        // complete(null) die NEUE gleich mit und schaltete so den Sync aus.
        return antwort.await() ?: emptySet()
    }

    fun complete(nummer: Long, result: Set<String>?) = anfragen.beantworte(nummer, result)
}

/**
 * Boulder: wer auf welchen Dialog wartet. Nur die juengste Anfrage ist offen -
 * kam ihr Dialog nie auf, haengt sonst jede weitere. Eine neue bricht die alte
 * ab, statt sie mit "nichts erteilt" zu beantworten.
 */
internal class Anfragen<T> {
    private var nummer = 0L
    private var offen: CompletableDeferred<T?>? = null

    @Synchronized
    fun neu(): Pair<Long, CompletableDeferred<T?>> {
        offen?.cancel()
        val antwort = CompletableDeferred<T?>()
        offen = antwort
        nummer++
        return nummer to antwort
    }

    /** Die Antwort eines abgeloesten Dialogs faellt weg: sie gehoert nicht zur offenen Anfrage. */
    @Synchronized
    fun beantworte(anfrage: Long, antwort: T?) {
        if (anfrage != nummer) return
        offen?.complete(antwort)
        offen = null
    }
}

/**
 * Translucent, no-history host that requests a set of Health Connect permissions in one prompt.
 */
class HealthConnectWritePermissionActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted: Set<String> ->
        HealthConnectPermissionBridge.complete(intent.getLongExtra(KEY_ANFRAGE, -1L), granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayExtra(KEY_PERMISSIONS)?.toSet().orEmpty()
        try {
            requestPermissionLauncher.launch(permissions)
        } catch (e: Exception) {
            logger.w(e) { "Failed launching Health Connect permission contract" }
            HealthConnectPermissionBridge.complete(intent.getLongExtra(KEY_ANFRAGE, -1L), null)
            finish()
        }
    }
}
