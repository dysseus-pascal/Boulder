package coredevices.pebble.firmware

// Boulder laeuft nicht auf iOS; ohne Pruefung gibt es dort auch kein Update.
actual fun ed25519Pruefen(nachricht: ByteArray, signatur: ByteArray, oeffentlich: ByteArray): Boolean? = null
