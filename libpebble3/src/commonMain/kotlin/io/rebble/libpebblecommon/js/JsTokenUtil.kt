package io.rebble.libpebblecommon.js

import io.ktor.utils.io.core.toByteArray
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.services.WatchInfo
import okio.Buffer
import kotlin.uuid.Uuid

class JsTokenUtil(
    private val tokenProvider: TokenProvider,
    private val lockerEntryRealDao: LockerEntryRealDao,
) {
    companion object {
        private const val ACCOUNT_TOKEN_SALT =
            "MMIxeUT[G9/U#(7V67O^EuADSw,{\$C;B}`>|-nlrQCs|t|k=P_!*LETm,RKc,BG*'"
    }

    private fun md5Digest(input: String): String {
        val data = Buffer()
        data.write(input.toByteArray())
        return data.md5().hex().lowercase()
    }

    private suspend fun generateToken(uuid: Uuid, developerId: String?, seed: String): String {
        val unhashed = buildString {
            append(seed)
            append(developerId ?: uuid.toString().uppercase())
            append(ACCOUNT_TOKEN_SALT)
        }
        return md5Digest(unhashed)
    }

    suspend fun getWatchToken(uuid: Uuid, developerId: String?, watchInfo: WatchInfo): String {
        val serial = watchInfo.serial
        return generateToken(uuid, developerId, serial)
    }

    suspend fun getAccountToken(uuid: Uuid): String? {
        val devToken = tokenProvider.getDevToken() ?: return null
        val devId = lockerEntryRealDao.getEntry(uuid)?.appstoreData?.developerId
        return generateToken(uuid, devId ?: uuid.toString().uppercase(), devToken)
    }

    suspend fun getSandboxTimelineToken(uuid: Uuid): String? {
        //TODO: Get sandbox timeline token from API
        //RWS.timelineClientFlow.filterNotNull().first().getSandboxUserToken(uuid.toString())
        return null
    }
}