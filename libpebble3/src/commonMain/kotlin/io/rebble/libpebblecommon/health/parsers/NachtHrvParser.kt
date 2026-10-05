package io.rebble.libpebblecommon.health.parsers

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

private val logger = Logger.withTag("NachtHrvParser")

/** Ein Satz auf Tag 89: ein Teil der HRV-Fenster einer Nacht. */
data class NachtHrvTeil(
    val beginnUtc: Long,
    val teil: Int,
    val teile: Int,
    val fenster: List<NachtHrvEntity>,
)

/**
 * Die HRV-Fenster der Boulder-Firmware auf Tag 89 (NachtHrvDlsRecord in
 * boulder/nacht.h): version, size (u16), beginn_utc (u32), teil, teile,
 * anzahl (u8), dann 48 Fenster zu je minute, rmssd (u16) und puls (u8).
 * Little Endian, gepackt. [beginnUtc] ist derselbe wie im Satz der Nacht auf
 * Tag 88; darueber gehoeren die beiden zusammen.
 *
 * EINE UNBEKANNTE FASSUNG wird uebersprungen statt geraten: Bit 0 der Version
 * muss gesetzt sein, wie bei den Naechten. Ebenso ein Satz, der nicht die Groesse
 * eines HRV-Satzes hat. Jeder verworfene Satz geht mit Grund an [verworfen].
 *
 * [vorwaerts] (die neuen Tags, BoulderDls): die Fassung zaehlt aufwaerts statt
 * als Bitfeld, und ein groesserer Satz einer neueren Firmware wird bis zu den
 * 48 Fenstern gelesen - der Rest ist dort neu.
 */
fun parseNachtHrvData(
    payload: ByteArray,
    itemSize: UShort,
    vorwaerts: Boolean = false,
    verworfen: (String) -> Unit = {},
): List<NachtHrvTeil> {
    val groesse = itemSize.toInt()
    if (payload.isEmpty()) return emptyList()
    if (groesse < HRV_SATZ_BYTES) {
        val grund = "Nacht-HRV: item size $groesse < $HRV_SATZ_BYTES, ${payload.size} bytes dropped"
        logger.w { grund }
        verworfen(grund)
        return emptyList()
    }
    if (payload.size % groesse != 0) {
        val grund = "Nacht-HRV: ${payload.size % groesse} bytes after the last whole record dropped"
        logger.w { grund }
        verworfen(grund)
    }
    if (vorwaerts && groesse > HRV_SATZ_BYTES) {
        logger.i { "Nacht-HRV: ${groesse - HRV_SATZ_BYTES} bytes per record are newer than this app; not read" }
    }
    val aus = mutableListOf<NachtHrvTeil>()
    for (satz in 0 until payload.size / groesse) {
        val b = DataBuffer(payload.copyOfRange(satz * groesse, (satz + 1) * groesse).toUByteArray())
        b.setEndian(Endian.Little)
        val version = b.getUShort().toInt()
        val satzGroesse = b.getUShort().toInt()
        val bekannt = if (vorwaerts) version >= 1 else version and 1 != 0
        if (!bekannt || satzGroesse != groesse) {
            val grund = "Nacht-HRV: record $satz dropped (version $version, size $satzGroesse, item size $groesse)"
            logger.w { grund }
            verworfen(grund)
            continue
        }
        val beginn = b.getUInt().toLong()
        val teil = b.getUByte().toInt()
        val teile = b.getUByte().toInt()
        val anzahl = b.getUByte().toInt()
        // Nie ueber den Satz hinaus lesen, auch wenn die Anzahl mehr behauptet.
        val passen = minOf(HRV_FENSTER_JE_SATZ, (groesse - HRV_KOPF_BYTES) / HRV_FENSTER_BYTES)
        val fenster = (0 until minOf(anzahl, passen)).map {
            NachtHrvEntity(
                beginnUtc = beginn,
                minute = b.getUShort().toInt(),
                rmssd = b.getUShort().toInt(),
                puls = b.getUByte().toInt(),
            )
        }
        aus += NachtHrvTeil(beginn, teil, teile, fenster.filter { it.rmssd > 0 })
    }
    return aus
}

private const val HRV_KOPF_BYTES = 11
private const val HRV_FENSTER_BYTES = 5
private const val HRV_FENSTER_JE_SATZ = 48
private const val HRV_SATZ_BYTES = HRV_KOPF_BYTES + HRV_FENSTER_JE_SATZ * HRV_FENSTER_BYTES
