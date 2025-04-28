package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.database.entity.asMetadata
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class LockerTest {
    @Test
    fun versionParser() {
        val id = Uuid.random()
        val platform = LockerEntryPlatform(
            lockerEntryId = id,
            sdkVersion = "1.2",
            processInfoFlags = 0,
            name = "aplite",
            screenshotImageUrl = "",
            listImageUrl = "",
            iconImageUrl = "",
            pbwIconResourceId = 0,
        )
        val entry = LockerEntry(
            id = id,
            version = "12.13-rbl1",
            title = "test",
            type = "watchface",
            developerName = "core",
            configurable = false,
            pbwVersionCode = "0",
            sideloaded = false,
            appstoreData = null,
            platforms = listOf(platform),
        )

        val metadata = entry.asMetadata(WatchType.APLITE)
        assertEquals(12u, metadata!!.appVersionMajor.get())
        assertEquals(13u, metadata!!.appVersionMinor.get())
        assertEquals(1u, metadata!!.sdkVersionMajor.get())
        assertEquals(2u, metadata!!.sdkVersionMinor.get())
    }
}