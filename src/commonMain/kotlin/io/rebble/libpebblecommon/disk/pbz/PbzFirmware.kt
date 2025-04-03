package io.rebble.libpebblecommon.disk.pbz

import io.rebble.libpebblecommon.disk.pbz.DiskUtil.requirePbzManifest
import kotlinx.io.files.Path

class PbzFirmware(private val path: Path) {
    val manifest by lazy { requirePbzManifest(path) }
    fun getFile(fileName: String) = DiskUtil.getFile(path, fileName)
}