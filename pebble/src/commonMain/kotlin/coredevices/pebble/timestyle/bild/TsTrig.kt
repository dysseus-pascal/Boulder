package coredevices.pebble.timestyle.bild

/*
 * DIE GANZZAHL-TRIGONOMETRIE DER FIRMWARE (lib/util/trig.c, lib/util/math.c),
 * Wert fuer Wert. Die Vorschau muss dieselben Rundungen treffen wie die Uhr:
 * schon ein um eins anderer Sinus verschiebt eine Kante um einen Pixel.
 * Die Tabellen sind Daten der Firmware (dort von tintin/tools/trigTable.py
 * erzeugt), keine eigene Rechnung.
 */

internal const val TRIG_MAX_RATIO = 0xffff
internal const val TRIG_MAX_ANGLE = 0x10000
internal const val TRIG_PI = 0x8000

private val SIN_TABELLE = intArrayOf(
    0, 401, 801, 1202, 1602, 2002, 2403, 2803, 3203, 3603, 4003, 4403, 4802, 5202, 5601, 6000,
    6399, 6797, 7195, 7593, 7991, 8389, 8786, 9182, 9579, 9975, 10371, 10766, 11161, 11555, 11949,
    12343, 12736, 13129, 13521, 13913, 14304, 14694, 15085, 15474, 15863, 16251, 16639, 17026,
    17413, 17798, 18184, 18568, 18952, 19335, 19717, 20099, 20480, 20860, 21239, 21618, 21996,
    22372, 22749, 23124, 23498, 23872, 24244, 24616, 24987, 25356, 25725, 26093, 26460, 26826,
    27191, 27555, 27918, 28280, 28641, 29000, 29359, 29717, 30073, 30428, 30783, 31136, 31487,
    31838, 32188, 32536, 32883, 33229, 33574, 33917, 34259, 34600, 34939, 35278, 35614, 35950,
    36284, 36617, 36949, 37279, 37608, 37935, 38261, 38585, 38908, 39230, 39550, 39869, 40186,
    40502, 40816, 41128, 41439, 41749, 42057, 42363, 42668, 42971, 43273, 43573, 43871, 44168,
    44463, 44757, 45048, 45338, 45627, 45913, 46198, 46482, 46763, 47043, 47321, 47597, 47872,
    48144, 48415, 48684, 48951, 49217, 49480, 49742, 50002, 50260, 50516, 50770, 51023, 51273,
    51521, 51768, 52013, 52255, 52496, 52735, 52972, 53207, 53440, 53670, 53899, 54126, 54351,
    54574, 54794, 55013, 55230, 55444, 55657, 55867, 56076, 56282, 56486, 56688, 56888, 57086,
    57281, 57475, 57666, 57856, 58043, 58228, 58410, 58591, 58769, 58945, 59119, 59291, 59461,
    59628, 59793, 59956, 60116, 60275, 60431, 60585, 60736, 60886, 61033, 61177, 61320, 61460,
    61598, 61734, 61867, 61998, 62126, 62253, 62377, 62498, 62618, 62735, 62850, 62962, 63072,
    63179, 63285, 63388, 63488, 63586, 63682, 63775, 63866, 63955, 64041, 64125, 64206, 64286,
    64362, 64436, 64508, 64578, 64645, 64709, 64771, 64831, 64889, 64943, 64996, 65046, 65094,
    65139, 65182, 65222, 65260, 65295, 65328, 65359, 65387, 65413, 65436, 65457, 65475, 65491,
    65504, 65515, 65524, 65530, 65534, 65535,
)

private val ATAN_TABELLE = intArrayOf(
    0, 328, 648, 976, 1296, 1624, 1944, 2272, 2600, 2920, 3248, 3568, 3896, 4216, 4544, 4864, 5192,
    5512, 5832, 6160, 6480, 6800, 7128, 7448, 7768, 8088, 8416, 8736, 9056, 9376, 9696, 10016,
    10336, 10656, 10976, 11296, 11616, 11928, 12248, 12568, 12880, 13200, 13520, 13832, 14152,
    14464, 14776, 15096, 15408, 15720, 16032, 16344, 16656, 16968, 17280, 17592, 17904, 18216,
    18520, 18832, 19136, 19448, 19752, 20056, 20368, 20672, 20976, 21280, 21584, 21888, 22192,
    22488, 22792, 23096, 23392, 23696, 23992, 24288, 24584, 24888, 25184, 25480, 25768, 26064,
    26360, 26656, 26944, 27240, 27528, 27816, 28104, 28400, 28688, 28976, 29256, 29544, 29832,
    30112, 30400, 30680, 30960, 31248, 31528, 31808, 32088, 32368, 32640, 32920, 33192, 33472,
    33744, 34024, 34296, 34568, 34840, 35112, 35376, 35648, 35920, 36184, 36448, 36720, 36984,
    37248, 37512, 37776, 38040, 38296, 38560, 38816, 39080, 39336, 39592, 39848, 40104, 40360,
    40616, 40864, 41120, 41368, 41624, 41872, 42120, 42368, 42616, 42864, 43112, 43352, 43600,
    43840, 44088, 44328, 44568, 44808, 45048, 45288, 45520, 45760, 45992, 46232, 46464, 46696,
    46928, 47160, 47392, 47624, 47856, 48080, 48312, 48536, 48768, 48992, 49216, 49440, 49664,
    49880, 50104, 50328, 50544, 50768, 50984, 51200, 51416, 51632, 51848, 52064, 52272, 52488,
    52696, 52912, 53120, 53328, 53536, 53744, 53952, 54160, 54368, 54568, 54776, 54976, 55184,
    55384, 55584, 55784, 55984, 56184, 56384, 56576, 56776, 56968, 57168, 57360, 57552, 57744,
    57936, 58128, 58320, 58512, 58704, 58888, 59080, 59264, 59448, 59632, 59824, 60008, 60184,
    60368, 60552, 60736, 60912, 61096, 61272, 61456, 61632, 61808, 61984, 62160, 62336, 62512,
    62680, 62856, 63032, 63200, 63368, 63544, 63712, 63880, 64048, 64216, 64384, 64552, 64720,
    64880, 65048, 65208, 65376, 65535,
)

/** sin_lookup: Winkel in TRIG_MAX_ANGLE, Ergebnis in TRIG_MAX_RATIO. */
internal fun sinTabelle(winkel: Int): Int {
    var angle = winkel
    var mult = 1
    if (angle < 0) {
        angle = -angle
        mult = -mult
    }
    if (angle >= TRIG_MAX_ANGLE) angle %= TRIG_MAX_ANGLE
    if (angle >= TRIG_MAX_ANGLE / 2) {
        mult = -mult
        angle -= TRIG_MAX_ANGLE / 2
    }
    if (angle >= TRIG_MAX_ANGLE / 4 && angle <= TRIG_MAX_ANGLE / 2) angle = TRIG_MAX_ANGLE / 2 - angle
    val lookup = angle * 4 / 0xff
    if (lookup + 1 < SIN_TABELLE.size) {
        return mult * (SIN_TABELLE[lookup] + ((angle * 4) % 0xff) * (SIN_TABELLE[lookup + 1] - SIN_TABELLE[lookup]) / 0xff)
    }
    return mult * SIN_TABELLE[lookup]
}

/** cos_lookup */
internal fun cosTabelle(winkel: Int): Int = sinTabelle(winkel + TRIG_MAX_ANGLE / 4)

/** atan2_lookup - die Argumente sind in C int16_t. */
internal fun atan2Tabelle(yIn: Int, xIn: Int): Int {
    val y16 = yIn.toShort().toInt()
    val x16 = xIn.toShort().toInt()
    if (y16 == 0) return if (x16 >= 0) 0 else TRIG_PI
    var x = x16
    var y = y16
    var angle = 0
    if (y < 0) {
        x = -x
        y = -y
        angle += 4
    }
    if (x <= 0) {
        val t = x
        x = y
        y = -t
        angle += 2
    }
    if (x <= y) {
        val t = y - x
        x += y
        y = t
        angle += 1
    }
    angle *= TRIG_PI / 4
    // In C: uint32_t ratio = (y << 16) / x, mit int32-Rechnung davor.
    val ratio = ((y shl 16) / x).toLong() and 0xffffffffL
    return angle + ATAN_TABELLE[(ratio / 0xff).toInt()] / 8
}

/** normalize_angle */
internal fun normalisiereWinkel(winkel: Int): Int {
    var n = kotlin.math.abs(winkel) % TRIG_MAX_ANGLE
    if (winkel < 0) n = TRIG_MAX_ANGLE - n
    return n
}

/** integer_sqrt: Newton mit hoechstens 15 Schritten - NICHT immer floor(sqrt), darum nachgebaut. */
internal fun ganzzahlWurzel(x: Long): Int {
    if (x < 0) return 0
    var last = 0x3fffL
    var schritte = 0
    while (last > 0 && schritte < 15) {
        last = (x / last + last) / 2
        schritte++
    }
    return last.toInt()
}
