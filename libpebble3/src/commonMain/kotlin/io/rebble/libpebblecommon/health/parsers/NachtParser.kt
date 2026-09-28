package io.rebble.libpebblecommon.health.parsers

import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

/** Ein Satz auf Tag 88: die Kopfwerte der Nacht und ein Teil ihrer Abschnitte. */
data class NachtTeil(
    val nacht: NachtEntity,
    val teil: Int,
    val teile: Int,
    val abschnitte: List<NachtAbschnittEntity>,
)

/**
 * Die Saetze der Boulder-Firmware auf Tag 88 (NachtDlsRecord in
 * boulder/nacht.h): version, size (u16), beginn_utc (u32), utc_to_local
 * (i32), einschlafen, aufwachen, schlaf_minuten (u16), ruhepuls, teil, teile
 * (u8), hrv (u16), anzahl (u8), dann 60 Abschnitte zu je von, bis (u16) und
 * phase (u8). Little Endian, gepackt.
 *
 * EINE UNBEKANNTE FASSUNG wird uebersprungen statt geraten: Bit 0 der Version
 * muss gesetzt sein, wie bei den Sitzungen.
 */
fun parseNachtData(payload: ByteArray, itemSize: UShort): List<NachtTeil> {
    val groesse = itemSize.toInt()
    if (payload.isEmpty() || groesse < KOPF_BYTES) return emptyList()
    val aus = mutableListOf<NachtTeil>()
    for (satz in 0 until payload.size / groesse) {
        val b = DataBuffer(payload.copyOfRange(satz * groesse, (satz + 1) * groesse).toUByteArray())
        b.setEndian(Endian.Little)
        val version = b.getUShort().toInt()
        b.getUShort() // size
        if (version and 1 == 0) continue
        val beginn = b.getUInt().toLong()
        val zuLokal = b.getInt()
        val einschlafen = b.getUShort().toInt()
        val aufwachen = b.getUShort().toInt()
        val schlaf = b.getUShort().toInt()
        val ruhepuls = b.getUByte().toInt()
        val teil = b.getUByte().toInt()
        val teile = b.getUByte().toInt()
        val hrv = b.getUShort().toInt()
        val anzahl = b.getUByte().toInt()
        val passen = minOf(ABSCHNITTE_JE_SATZ, (groesse - KOPF_BYTES) / ABSCHNITT_BYTES)
        val abschnitte = (0 until minOf(anzahl, passen)).map {
            NachtAbschnittEntity(
                beginnUtc = beginn,
                von = b.getUShort().toInt(),
                bis = b.getUShort().toInt(),
                phase = b.getUByte().toInt(),
            )
        }
        // AB FASSUNG 3 (Bit 1): hinter den 60 Abschnitten Akku und Messstufe. Die
        // Stelle ist fest, egal wie viele Abschnitte belegt sind.
        var nacht = NachtEntity(beginn, zuLokal, einschlafen, aufwachen, schlaf, ruhepuls, hrv)
        if (version and 2 != 0 && groesse >= ANHANG_OFFSET + 4) {
            b.getBytes(ANHANG_OFFSET - (KOPF_BYTES + abschnitte.size * ABSCHNITT_BYTES))
            val akkuBeginn = b.getUByte().toInt()
            val akkuEnde = b.getUByte().toInt()
            val flags = b.getUByte().toInt()
            val stufe = b.getUByte().toInt()
            nacht = nacht.copy(
                akkuBeginn = akkuBeginn.takeIf { it != 0xFF },
                akkuEnde = akkuEnde.takeIf { it != 0xFF },
                geladen = flags and 1 != 0,
                messstufe = stufe.takeIf { it in 1..5 },
            )
        }
        aus += NachtTeil(nacht, teil, teile, abschnitte)
    }
    return aus
}

private const val KOPF_BYTES = 24
private const val ABSCHNITT_BYTES = 5
private const val ABSCHNITTE_JE_SATZ = 60
private const val ANHANG_OFFSET = KOPF_BYTES + ABSCHNITTE_JE_SATZ * ABSCHNITT_BYTES
