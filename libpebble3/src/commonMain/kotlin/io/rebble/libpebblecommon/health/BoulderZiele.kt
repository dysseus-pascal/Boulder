package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.database.entity.HealthStat

/**
 * Boulder: die Ziele fuer Schritte und Schlaf, wie die Boulder-Firmware V2 sie
 * liest - der Satz BoulderZieleSatz (schnittstelle.h) unter [SCHLUESSEL] in der
 * Datenbank Health. Die Grenzen sind die der Firmware (ziele.h): einen Satz
 * ausserhalb verwirft sie ganz und zeigt wieder die Vorgabe.
 *
 * Der Schluessel geht ohne Nullbyte zur Uhr (HealthStat.key); nur so liest die
 * Firmware ihn.
 */
object BoulderZiele {
    const val SCHLUESSEL = "boulder_ziele"
    const val FASSUNG = 1

    const val SCHRITTE_VORGABE = 10_000
    const val SCHRITTE_MIN = 1_000
    const val SCHRITTE_MAX = 50_000

    // Schlaf in Minuten.
    const val SCHLAF_VORGABE = 480
    const val SCHLAF_MIN = 240
    const val SCHLAF_MAX = 720

    /** Die 8 Byte, little endian: Fassung, Reserve, Schritte (u16), Minuten Schlaf (u16), Reserve (u16). */
    fun satz(schritte: Int, schlafMin: Int): ByteArray {
        require(schritte in SCHRITTE_MIN..SCHRITTE_MAX) { "Schrittziel $schritte ausserhalb $SCHRITTE_MIN..$SCHRITTE_MAX" }
        require(schlafMin in SCHLAF_MIN..SCHLAF_MAX) { "Schlafziel $schlafMin ausserhalb $SCHLAF_MIN..$SCHLAF_MAX" }
        return byteArrayOf(
            FASSUNG.toByte(), 0,
            schritte.toByte(), (schritte shr 8).toByte(),
            schlafMin.toByte(), (schlafMin shr 8).toByte(),
            0, 0,
        )
    }

    /** Die Zeile der Datenbank Health, die die Synchronisation zur Uhr bringt. */
    fun zeile(schritte: Int, schlafMin: Int): HealthStat = HealthStat(SCHLUESSEL, satz(schritte, schlafMin))
}
