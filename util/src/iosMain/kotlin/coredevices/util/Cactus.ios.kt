package coredevices.util

import co.touchlab.kermit.Logger
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual fun getModelDirectories(): List<String> {
    val cachesDir = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory, NSUserDomainMask, true
    ).first() as String
    val docsDir = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).first() as String
    val voskModelPaths = SystemFileSystem.list(Path(cachesDir))
        .filter { it.name.startsWith("vosk-") }
        .map { it.toString() }
    val whisperPathsA = try {
        SystemFileSystem.list(Path(cachesDir))
    } catch (e: FileNotFoundException) {
        emptyList()
    }
    val whisperPathsB = try {
        SystemFileSystem.list(Path(docsDir, "models"))
    } catch (e: FileNotFoundException) {
        emptyList()
    }
    val whisperPathsC = try {
        SystemFileSystem.list(Path("$cachesDir/models"))
    } catch (e: FileNotFoundException) {
        emptyList()
    }
    val whisperModelPaths = (whisperPathsA + whisperPathsB + whisperPathsC).filter {
        it.name.startsWith("whisper-")
    }.map { it.toString() }
    return listOf(
        "$cachesDir/models/vosk",
        // legacy path
        "$cachesDir/models/vosk",
        "$cachesDir/vosk-model"
    ) + voskModelPaths + whisperModelPaths
}

actual fun calculateDefaultSTTModel(): String = CommonBuildKonfig.CACTUS_DEFAULT_STT_MODEL_IOS