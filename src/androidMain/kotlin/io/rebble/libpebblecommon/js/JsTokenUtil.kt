package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectedWatchInfo
import io.rebble.libpebblecommon.services.WatchInfo
import java.security.MessageDigest
import java.util.Locale
import kotlin.uuid.Uuid

object JsTokenUtil {
    private const val ACCOUNT_TOKEN_SALT = "MMIxeUT[G9/U#(7V67O^EuADSw,{\$C;B}`>|-  lrQCs|t|k=P_!*LETm,RKc,BG*'"

    private fun md5Digest(input: String): String {
        val digest = MessageDigest.getInstance("md5")
        digest.update(input.toByteArray())
        val bytes = digest.digest()
        return bytes.joinToString(separator = "") { String.format("%02X", it) }.lowercase(Locale.US)
    }

    private suspend fun generateToken(uuid: Uuid, developerId: String?, seed: String): String {
        val unhashed = buildString {
            append(seed)
            append(developerId ?: uuid.toString().uppercase(Locale.US))
            append(ACCOUNT_TOKEN_SALT)
        }
        return md5Digest(unhashed)
    }

    suspend fun getWatchToken(uuid: Uuid, developerId: String?, watchInfo: WatchInfo): String {
        val serial = watchInfo.serial
        return generateToken(uuid, developerId, serial)
    }

    suspend fun getAccountToken(uuid: Uuid): String? {
        //TODO: Get account token from API
        //RWS.authClientFlow.filterNotNull().first().getCurrentAccount().uid.toString().let { generateToken(uuid, it) }
        return null
    }

    suspend fun getSandboxTimelineToken(uuid: Uuid): String? {
        //TODO: Get sandbox timeline token from API
        //RWS.timelineClientFlow.filterNotNull().first().getSandboxUserToken(uuid.toString())
        return null
    }
}