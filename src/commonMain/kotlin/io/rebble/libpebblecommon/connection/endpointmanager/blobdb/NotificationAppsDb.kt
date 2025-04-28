package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import co.touchlab.kermit.Logger
import com.oldguy.common.getUIntAt
import com.oldguy.common.getULongAt
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.database.dao.NotificationAppDao
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.database.entity.BlobDBItemSyncStatus
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem.Action
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem.Attribute
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.structmapper.SFixedList
import io.rebble.libpebblecommon.structmapper.SString
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.structmapper.StructMappable
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlin.time.Instant

class NotificationAppsDb(
    watchScope: ConnectionCoroutineScope,
    blobDBService: BlobDBService,
    blobDBDao: BlobDBDao,
    transport: Transport,
    private val notificationAppDao: NotificationAppDao,
) : BlobDB(
    watchScope,
    blobDBService,
    BlobCommand.BlobDatabase.CannedResponses,
    blobDBDao,
    transport,
) {
    private val logger = Logger.withTag("NotificationAppsDb-$watchIdentifier")

    override suspend fun handleWrite(write: DbWrite): BlobResponse.BlobStatus {
        val key = write.key.asByteArray().decodeToString()
//        blobDBDao.insert(
//            BlobDBItem(
//                id = key,
//                syncStatus = BlobDBItemSyncStatus.SyncedToWatch,
//                watchIdentifier = transport.identifier.asString,
//                watchDatabase = write.database,
//                data = write.value.asByteArray(),
//            )
//        )
        val item = try {
            NotificationAppItem().apply { fromBytes(DataBuffer(write.value)) }
        } catch (e: Exception) {
            logger.w("NotificationAppItem ${e.message}", e)
            return BlobResponse.BlobStatus.TryLater
        }
        item.attributes.list.forEach {
            val type = TimelineAttribute.fromByte(it.attributeId.get())
            logger.d("key=$key / attribute: type=$type value=${it.content.get().joinToString()}")
        }
        try {
            val appName =
                item.attributes.get(TimelineAttribute.AppName)?.asByteArray()?.decodeToString()
            logger.d("appName=$appName")
            val mutedState = item.attributes.get(TimelineAttribute.MuteDayOfWeek)
                ?.let { MuteState.fromValue(it[0]) }
            logger.d("mutedState=$mutedState")
            val lastUpdated = item.attributes.get(TimelineAttribute.LastUpdated)
                ?.getUIntAt(0, littleEndian = false)?.let { Instant.fromEpochSeconds(it.toLong()) }
            logger.d("lastUpdated=$lastUpdated")
            if (appName != null && mutedState != null && lastUpdated != null) {
                notificationAppDao.updateFromWatch(
                    packageName = key,
                    appName = appName,
                    mutedState = mutedState,
                    lastUpdated = lastUpdated,
                )
            }
        } catch (e: Exception) {
            logger.d("decoding app record ${e.message}", e)
        }
        return BlobResponse.BlobStatus.Success
    }

    override fun idAsBytes(id: String): UByteArray = SString(StructMapper(), id).toBytes()
}

private fun SFixedList<Attribute>.get(attribute: TimelineAttribute): UByteArray? =
    list.find { it.attributeId.get() == attribute.id }?.content?.get()

class NotificationAppItem(
    flags: UInt = 0u,
    attributes: List<Attribute> = emptyList(),
    actions: List<Action> = emptyList()
) : StructMappable() {
    val flags = SUInt(m, flags, endianness = Endian.Little)
    val attrCount = SUByte(m, attributes.size.toUByte())
    val actionCount = SUByte(m, actions.size.toUByte())
    val attributes = SFixedList(m, attrCount.get().toInt(), attributes) {
        Attribute(0u, ubyteArrayOf())
    }.apply {
        linkWithCount(attrCount)
    }
    val actions = SFixedList(m, actionCount.get().toInt(), actions) {
        Action(
            0u,
            Action.Type.Empty,
            emptyList()
        )
    }.apply {
        linkWithCount(actionCount)
    }
}