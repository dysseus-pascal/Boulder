package io.rebble.libpebblecommon.disk.pbw

import io.rebble.libpebblecommon.disk.PbwBinHeader
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.pkjsFileExists
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwAppInfo
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwBinaryBlob
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwManifest
import io.rebble.libpebblecommon.disk.pbw.DiskUtil.requirePbwPKJSFile
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class PbwApp(private val path: Path) {
    val info by lazy { requirePbwAppInfo(path) }
    val hasPKJS by lazy { pkjsFileExists(path) }
    fun getManifest(watchType: WatchType) = requirePbwManifest(path, watchType)
    fun getBinaryFor(watchType: WatchType): Source {
        val filename = getManifest(watchType).application.name
        return requirePbwBinaryBlob(path, watchType, filename)
    }
    fun getResourcesFor(watchType: WatchType): Source? {
        val resources = getManifest(watchType).resources ?: return null
        return requirePbwBinaryBlob(path, watchType, resources.name)
    }
    fun getBinaryHeaderFor(watchType: WatchType): PbwBinHeader {
        return getBinaryFor(watchType).use { source ->
            PbwBinHeader.parseFileHeader(source.readByteArray(PbwBinHeader.SIZE).asUByteArray())
        }
    }
    fun getPKJSFile(): Source {
        return requirePbwPKJSFile(path)
    }
    fun source(fileSystem: FileSystem = SystemFileSystem): RawSource {
        return fileSystem.source(path)
    }
}