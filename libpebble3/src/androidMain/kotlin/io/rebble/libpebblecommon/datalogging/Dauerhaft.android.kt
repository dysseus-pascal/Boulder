package io.rebble.libpebblecommon.datalogging

import kotlinx.io.files.Path
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal actual fun schreibeDauerhaft(pfad: Path, daten: ByteArray) {
    FileOutputStream(pfad.toString()).use { aus ->
        aus.write(daten)
        // fsync: close allein laesst die Bytes im Seitencache.
        aus.fd.sync()
    }
}

internal actual fun ordnerDauerhaft(ordner: Path) {
    // Ein Ordner laesst sich nur lesend oeffnen; force ist dann fsync auf ihm.
    FileChannel.open(Paths.get(ordner.toString()), StandardOpenOption.READ).use { it.force(true) }
}
