package io.rebble.libpebblecommon.datalogging

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
internal actual fun schreibeDauerhaft(pfad: Path, daten: ByteArray) {
    // 0644 wie eine gewoehnlich angelegte Datei.
    val fd = open(pfad.toString(), O_WRONLY or O_CREAT or O_TRUNC, 0x1A4)
    if (fd < 0) throw IOException("open $pfad failed: errno $errno")
    try {
        var geschrieben = 0
        // Ein leeres Feld hat keine Adresse; dann gibt es nichts zu schreiben.
        if (daten.isNotEmpty()) {
            daten.usePinned { p ->
                while (geschrieben < daten.size) {
                    val n = write(fd, p.addressOf(geschrieben), (daten.size - geschrieben).convert())
                    if (n < 0) throw IOException("write $pfad failed: errno $errno")
                    geschrieben += n.toInt()
                }
            }
        }
        if (fsync(fd) != 0) throw IOException("fsync $pfad failed: errno $errno")
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun ordnerDauerhaft(ordner: Path) {
    val fd = open(ordner.toString(), O_RDONLY)
    if (fd < 0) throw IOException("open $ordner failed: errno $errno")
    try {
        if (fsync(fd) != 0) throw IOException("fsync $ordner failed: errno $errno")
    } finally {
        close(fd)
    }
}
