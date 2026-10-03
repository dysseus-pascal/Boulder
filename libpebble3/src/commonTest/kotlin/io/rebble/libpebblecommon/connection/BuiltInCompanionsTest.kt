package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boulder: wer neben PebbleKit JS die Nachrichten der Uhr bestaetigt. Genau
 * einer muss es tun - keiner, und die Uhr wiederholt jede Nachricht; der
 * falsche, und sie gibt ihre Kopie auf, bevor sie gesichert ist.
 */
class BuiltInCompanionsTest {
    private open class Begleiter : CompanionApp {
        override suspend fun start(incomingAppMessages: Flow<AppMessageData>) {}
        override suspend fun stop() {}
    }

    private class Sichernder : Begleiter(), BestaetigtSelbst

    @Test
    fun ohne_begleiter_bestaetigt_pebblekit_js() {
        assertTrue(pkjsBestaetigt(null))
    }

    @Test
    fun neben_einem_gewoehnlichen_begleiter_bestaetigt_pebblekit_js() {
        assertTrue(pkjsBestaetigt(Begleiter()))
    }

    @Test
    fun neben_einem_sichernden_begleiter_schweigt_pebblekit_js() {
        assertFalse(pkjsBestaetigt(Sichernder()))
    }
}
