package io.rebble.libpebblecommon.disk.pbz

import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzManifest
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.readString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.openZip

object DiskUtil {
    private const val MANIFEST_FILENAME = "manifest.json"
    private val pbzJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun openZip(path: Path) = FileSystem.SYSTEM.openZip(path.toString().toPath())
    fun getPbzManifest(pbzPath: Path): PbzManifest? {
        val source = try {
            openZip(pbzPath).source(MANIFEST_FILENAME.toPath()).asKotlinxIoRawSource()
        } catch (e: IOException) {
            return null
        }.buffered()
        return pbzJson.decodeFromString(source.readString())
    }
    fun requirePbzManifest(pbzPath: Path): PbzManifest {
        return getPbzManifest(pbzPath)
            ?: throw IllegalStateException("Pbz does not contain manifest")
    }

    fun getFile(pbzPath: Path, fileName: String): RawSource = openZip(pbzPath).source(fileName.toPath()).asKotlinxIoRawSource()
}