package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import kotlin.uuid.Uuid

/**
 * Boulder: die Datalogging-Tags der Boulder-Firmware. Alle laufen unter
 * SYSTEM_APP_UUID (nacht.c); Tags anderer UUIDs gehoeren Watchapps und gehen
 * den Upstream-Weg.
 *
 * 88/89 sind die alten Tags. Upstream koennte sie einmal selbst belegen; als
 * Nacht gelten sie darum nur mit genau der Itemgroesse eines Nachtsatzes. Die
 * neuen 0x4001/0x4002 liegen im eigenen Bereich 0x4000-0x40FF und nehmen auch
 * groessere Saetze einer kuenftigen Firmware an (vorwaertsvertraeglich).
 */
object BoulderDls {
    const val NACHT_ALT: UInt = 88u
    const val HRV_ALT: UInt = 89u
    const val NACHT: UInt = 0x4001u
    const val HRV: UInt = 0x4002u
    val BEREICH: UIntRange = 0x4000u..0x40FFu

    // NachtDlsRecord in Fassung 1 (ohne Anhang) und 3 (mit Akku und Messstufe); NachtHrvDlsRecord.
    private val NACHT_ALT_GROESSEN = setOf(324, 328)
    private const val HRV_ALT_GROESSE = 251

    enum class Art { NACHT, HRV, UNBEKANNT }

    /** Was Boulder mit diesem Tag tut; null: nicht Boulders, der Upstream-Weg. */
    fun art(tag: UInt, uuid: Uuid, itemSize: UShort): Art? {
        if (uuid != SYSTEM_APP_UUID) return null
        return when (tag) {
            NACHT_ALT -> Art.NACHT.takeIf { itemSize.toInt() in NACHT_ALT_GROESSEN }
            HRV_ALT -> Art.HRV.takeIf { itemSize.toInt() == HRV_ALT_GROESSE }
            NACHT -> Art.NACHT
            HRV -> Art.HRV
            in BEREICH -> Art.UNBEKANNT
            else -> null
        }
    }

    /** Auf den neuen Tags gelten die vorwaertsvertraeglichen Regeln der Parser. */
    fun vorwaerts(tag: UInt): Boolean = tag in BEREICH
}
