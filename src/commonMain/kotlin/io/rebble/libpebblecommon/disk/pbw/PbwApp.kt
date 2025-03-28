package io.rebble.libpebblecommon.disk.pbw

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwAppInfo
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwBinaryBlob
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwManifest
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.io.Source
import kotlinx.io.files.Path

class PbwApp(private val path: Path) {
    val info by lazy { requirePbwAppInfo(path) }
    fun getManifest(watchType: WatchType) = requirePbwManifest(path, watchType)
    fun getBinaryFor(watchType: WatchType): Source {
        val filename = getManifest(watchType).application.name
        return requirePbwBinaryBlob(path, watchType, filename)
    }
    fun getResourcesFor(watchType: WatchType): Source? {
        val resources = getManifest(watchType).resources ?: return null
        return requirePbwBinaryBlob(path, watchType, resources.name)
    }
}