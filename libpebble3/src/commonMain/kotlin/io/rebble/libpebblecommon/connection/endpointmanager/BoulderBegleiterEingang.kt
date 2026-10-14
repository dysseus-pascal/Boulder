package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlin.uuid.Uuid

// Boulder: der Eingang je Begleiter. Neben einem Begleiter, der jede Nachricht sofort
// bestaetigt (BestaetigtSelbst), schickt die Uhr weiter, waehrend PebbleKit JS bis zu
// 6 s je Nachricht auf sein JS wartet - die Antworten der Uhr begrenzen diese Schlange
// also nicht. BUFFERED (64) verwarf dann still die neuesten, UNLIMITED wuechse ohne
// Grenze, wenn das JS nie bereit wird. Darum 256, und darueber geht die aelteste mit
// Log: eine minutenalte Nachricht nuetzt dem JS weniger als die juengste.
internal const val BEGLEITER_EINGANG = 256

internal fun begleiterEingang(an: Uuid): Channel<AppMessageData> = Channel(
    capacity = BEGLEITER_EINGANG,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
    onUndeliveredElement = { weg ->
        Logger.withTag("CompanionAppLifecycleManager").w {
            "App message ${weg.transactionId} for $an dropped: companion more than $BEGLEITER_EINGANG messages behind"
        }
    },
)

internal fun SendChannel<AppMessageData>.reicheWeiter(nachricht: AppMessageData, an: Uuid) {
    // Voll ist der Eingang nie (DROP_OLDEST); scheitern kann es nur an einem geschlossenen.
    trySend(nachricht).onFailure {
        Logger.withTag("CompanionAppLifecycleManager").e(it) { "App message ${nachricht.transactionId} for $an dropped" }
    }
}
