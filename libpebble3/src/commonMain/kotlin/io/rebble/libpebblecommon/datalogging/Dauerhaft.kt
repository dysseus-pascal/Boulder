package io.rebble.libpebblecommon.datalogging

import kotlinx.io.files.Path

/**
 * Boulder: schreibt [daten] nach [pfad] und kehrt erst zurueck, wenn sie auf dem
 * Datentraeger liegen (fsync), nicht nur im Seitencache des Systems. Wirft, wenn
 * das nicht ging.
 *
 * WARUM: gleich danach geht das ACK an die Uhr, und sie gibt ihre Kopie auf. Ohne
 * fsync laege nach einem Stromausfall oder Kernel-Absturz in den Sekunden danach
 * eine leere Datei da.
 */
internal expect fun schreibeDauerhaft(pfad: Path, daten: ByteArray)

/**
 * Boulder: bringt die Eintraege von [ordner] auf den Datentraeger - erst dann
 * ueberlebt ein eben umbenannter Name einen Stromausfall. Wirft, wenn das nicht ging.
 */
internal expect fun ordnerDauerhaft(ordner: Path)
