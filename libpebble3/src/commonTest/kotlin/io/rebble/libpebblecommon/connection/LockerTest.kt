package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class LockerTest {
    @Test
    fun versionParser() {
        val entry = LockerEntry(
            id = Uuid.random(),
            version = "12.13-rbl1",
            title = "test",
            type = "watchface",
            developerName = "core",
            configurable = false,
            pbwVersionCode = "0",
            pbwIconResourceId = 0,
            sideloaded = false,
            appstoreData = null,
        )
        val platform = LockerEntryPlatform(
            id = 0,
            lockerEntryId = entry.id,
            sdkVersion = "1.2",
            processInfoFlags = 0,
            name = "hello",
        )
        val metadata = entry.asMetaData(platform)
        assertEquals(12u, metadata!!.appVersionMajor.get())
        assertEquals(13u, metadata!!.appVersionMinor.get())
        assertEquals(1u, metadata!!.sdkVersionMajor.get())
        assertEquals(2u, metadata!!.sdkVersionMinor.get())
    }
}