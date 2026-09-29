package coredevices.pebble.health

import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity

/**
 * Boulder: die Naechte der Firmware (Datalogging-Tag 88) in die Gesundheitsakte -
 * Schlaf mit Phasen, Ruhepuls und HRV. health-kmp kennt weder REM noch
 * Ruhepuls noch HRV, also wie beim SpO2 ueber expect/actual.
 */
internal expect fun supportsNaechteWriting(): Boolean

/** Schreibt die Naechte; true, wenn alle angekommen sind. */
internal expect suspend fun writeNaechteToPlatform(naechte: List<Pair<NachtEntity, List<NachtAbschnittEntity>>>): Boolean

/** Die Spanne einer Nacht vom Einschlafen bis zum Aufwachen, in Sekunden seit 1970. */
internal fun NachtEntity.schlafSpanne(): LongRange =
    (beginnUtc + einschlafen * 60L)..(beginnUtc + aufwachen * 60L)
