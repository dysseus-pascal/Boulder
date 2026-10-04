package coredevices.pebble.kiesel.ablauf

import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.Wartend
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.libpebblecommon.services.appmessage.AppMessageTransactionSequence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import kotlin.uuid.Uuid

/** Die Uhr: merkt sich jede Antwort - und was in dem Augenblick in [ordner] lag. */
internal class FakeUhr(val ordner: File) : ConnectedPebble.AppMessages {
    val antworten = mutableListOf<Pair<AppMessageResult, List<KieselNachricht>>>()

    override val transactionSequence: Iterator<UByte> = AppMessageTransactionSequence().iterator()
    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult = AppMessageResult.ACK(appMessageData.transactionId)
    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {
        val liegt = ordner.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { Wartend.lies(it) }
        synchronized(antworten) { antworten += appMessageResult to liegt }
    }
    override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> = emptyFlow()
}

/** Das Geraet um [uhr]: die Begleiter fragen es nur nach Antworten, Kennung und Uhrdaten bleiben leer. */
internal fun geraet(uhr: FakeUhr): CompanionAppDevice {
    val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    val info = unsafe.allocateInstance(WatchInfo::class.java) as WatchInfo
    return CompanionAppDevice(object : PebbleIdentifier { override val asString = "test" }, info, uhr)
}
