package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.ObjectType
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class LanguagePackInstaller(
    private val putBytesSession: PutBytesSession,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : ConnectedPebble.Language {
    private val logger = Logger.withTag("LanguagePackInstaller")

    override suspend fun installLanguagePack(path: Path): Boolean = withContext(connectionCoroutineScope.coroutineContext) {
        val metadata = SystemFileSystem.metadataOrNull(path)
        if (metadata == null) {
            logger.e { "Failed to get metadata for $path" }
            return@withContext false
        }
        val source = SystemFileSystem.source(path).buffered()
        try {
            val installFlow = putBytesSession.beginSession(
                size = metadata.size.toUInt(),
                type = ObjectType.FILE,
                bank = 0u,
                filename = "lang",
                source = source,
                sendInstall = true,
            )
            installFlow.collect { state ->
//            when (state) {
//                is PutBytesSession.SessionState.Finished -> TODO()
//                is PutBytesSession.SessionState.Open -> TODO()
//                is PutBytesSession.SessionState.Sending -> TODO()
//            }
            }
            return@withContext true
        } catch (e: Exception) {
            logger.e(e) { "Error installing language pack" }
            return@withContext false
        } finally {
            source.close()
        }
    }
}
