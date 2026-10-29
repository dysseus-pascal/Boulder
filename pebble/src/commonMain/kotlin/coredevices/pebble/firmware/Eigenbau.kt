package coredevices.pebble.firmware

import io.rebble.libpebblecommon.services.FirmwareVersion

private val BOULDER_FASSUNG = Regex("""b\d+""")

/**
 * Boulder: ob die Uhr einen Eigenbau faehrt, dem Core weder ein Update meldet noch eins von selbst
 * aufspielt. Core erkennt ihn an jedem Suffix; Boulders Fassungen heissen aber immer vX.Y.Z-bN.
 * Ein Entwicklungsbau dahinter (-bN-2-gabcdef12) bleibt ein Eigenbau.
 */
fun FirmwareVersion.istEigenbau(): Boolean {
    val suffix = suffix
    return !suffix.isNullOrEmpty() && !BOULDER_FASSUNG.matches(suffix)
}
