package coredevices.pebble.health

import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity

internal actual fun supportsNaechteWriting(): Boolean = false

internal actual suspend fun writeNaechteToPlatform(naechte: List<Pair<NachtEntity, List<NachtAbschnittEntity>>>): Boolean = false
