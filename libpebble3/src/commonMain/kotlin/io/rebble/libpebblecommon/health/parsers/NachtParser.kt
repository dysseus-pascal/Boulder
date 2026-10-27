package io.rebble.libpebblecommon.health.parsers

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

private val logger = Logger.withTag("NachtParser")

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
 * muss gesetzt sein, wie bei den Sitzungen. Ebenso ein Satz, der nicht die
 * Groesse eines Nachtsatzes hat (Tag 88 koennte einmal Upstream belegen), und
 * einer mit unmoeglichem Teil. Jeder verworfene Satz geht mit Grund an [verworfen].
 *
 * [vorwaerts] (die neuen Tags, BoulderDls): die Fassung zaehlt aufwaerts statt
 * als Bitfeld, und gelesen wird NUR EINE BEKANNTE FASSUNG ([NACHT_FASSUNGEN]).
 * Deren bekannter Teil wird auch aus einem groesseren Satz gelesen (die Firmware
 * haengt nur hinten an). Eine neuere Fassung geht dagegen mit Grund an
 * [verworfen], also roh in die Quarantaene: ihr Aufbau ist hier unbekannt, und
 * geraten waeren Akku und Messstufe womoeglich Abschnittsbytes. Dasselbe gilt
 * fuer einen Satz, der mehr Abschnitte behauptet, als ein Satz fasst.
 */
fun parseNachtData(
    payload: ByteArray,
    itemSize: UShort,
    vorwaerts: Boolean = false,
    verworfen: (String) -> Unit = {},
): List<NachtTeil> {
    val groesse = itemSize.toInt()
    if (payload.isEmpty()) return emptyList()
    val mindestens = if (vorwaerts) ANHANG_OFFSET + ANHANG_BYTES else ANHANG_OFFSET
    if (groesse < mindestens) {
        melde(verworfen, "Nacht: item size $groesse < $mindestens, ${payload.size} bytes dropped")
        return emptyList()
    }
    if (payload.size % groesse != 0) {
        melde(verworfen, "Nacht: ${payload.size % groesse} bytes after the last whole record dropped")
    }
    if (vorwaerts && groesse > ANHANG_OFFSET + ANHANG_BYTES) {
        logger.w { "Nacht: item size $groesse > ${ANHANG_OFFSET + ANHANG_BYTES}; a known version is read up to its attachment, a newer one is quarantined" }
    }
    val aus = mutableListOf<NachtTeil>()
    for (satz in 0 until payload.size / groesse) {
        val b = DataBuffer(payload.copyOfRange(satz * groesse, (satz + 1) * groesse).toUByteArray())
        b.setEndian(Endian.Little)
        val version = b.getUShort().toInt()
        val satzGroesse = b.getUShort().toInt()
        val bekannt = if (vorwaerts) version in NACHT_FASSUNGEN else version and 1 != 0
        if (!bekannt) {
            melde(verworfen, "Nacht: record $satz has unknown version $version")
            continue
        }
        if (satzGroesse != groesse) {
            melde(verworfen, "Nacht: record $satz says size $satzGroesse, session item size is $groesse")
            continue
        }
        val beginn = b.getUInt().toLong()
        val zuLokal = b.getInt()
        val einschlafen = b.getUShort().toInt()
        val aufwachen = b.getUShort().toInt()
        val schlaf = b.getUShort().toInt()
        val ruhepuls = b.getUByte().toInt()
        val teil = b.getUByte().toInt()
        val teile = b.getUByte().toInt()
        // Eine Nacht ohne Abschnitte kommt als Teil 0 von 0 (nacht.c, prv_schicke).
        if (teil >= maxOf(teile, 1)) {
            melde(verworfen, "Nacht ab $beginn: part $teil of $teile is impossible")
            continue
        }
        val hrv = b.getUShort().toInt()
        val anzahl = b.getUByte().toInt()
        val passen = minOf(ABSCHNITTE_JE_SATZ, (groesse - KOPF_BYTES) / ABSCHNITT_BYTES)
        // Mehr Abschnitte, als der bekannte Aufbau fasst: kein Satz dieser Fassung.
        // Abgeschnitten fehlte der Rest der Nacht ohne Spur.
        if (anzahl > passen) {
            melde(verworfen, "Nacht ab $beginn: record $satz claims $anzahl sections, at most $passen fit")
            continue
        }
        val gelesen = (0 until minOf(anzahl, passen)).map {
            NachtAbschnittEntity(
                beginnUtc = beginn,
                von = b.getUShort().toInt(),
                bis = b.getUShort().toInt(),
                phase = b.getUByte().toInt(),
            )
        }
        // Die Firmware baut jeden Abschnitt mit von < bis und einer SchlafPhase 0..4.
        val abschnitte = gelesen.filter { it.von < it.bis && it.phase in PHASEN }
        if (abschnitte.size < gelesen.size) {
            melde(verworfen, "Nacht ab $beginn: ${gelesen.size - abschnitte.size} implausible sections dropped")
        }
        // AB FASSUNG 3 (Bit 1): hinter den 60 Abschnitten Akku und Messstufe. Die
        // Stelle ist fest, egal wie viele Abschnitte belegt sind.
        var nacht = NachtEntity(beginn, zuLokal, einschlafen, aufwachen, schlaf, ruhepuls, hrv)
        if (vorwaerts || (version and 2 != 0 && groesse >= ANHANG_OFFSET + ANHANG_BYTES)) {
            b.getBytes(ANHANG_OFFSET - (KOPF_BYTES + gelesen.size * ABSCHNITT_BYTES))
            val akkuBeginn = b.getUByte().toInt()
            val akkuEnde = b.getUByte().toInt()
            val flags = b.getUByte().toInt()
            val stufe = b.getUByte().toInt()
            nacht = nacht.copy(
                akkuBeginn = akkuBeginn.takeIf { it != 0xFF },
                akkuEnde = akkuEnde.takeIf { it != 0xFF },
                geladen = flags and FLAG_GELADEN != 0,
                manuell = flags and FLAG_MANUELL != 0,
                messstufe = stufe.takeIf { it in 1..5 },
            )
        }
        aus += NachtTeil(nacht, teil, teile, abschnitte)
    }
    return aus
}

// Kein stilles Verwerfen: die Uhr hat den Satz nach dem ACK nicht mehr.
private fun melde(verworfen: (String) -> Unit, grund: String) {
    logger.w { grund }
    verworfen(grund)
}

private const val KOPF_BYTES = 24
private const val ABSCHNITT_BYTES = 5
private const val ABSCHNITTE_JE_SATZ = 60
private const val ANHANG_OFFSET = KOPF_BYTES + ABSCHNITTE_JE_SATZ * ABSCHNITT_BYTES
private const val ANHANG_BYTES = 4
// Die Bits von flags (NACHT_DLS_FLAG_GELADEN, NACHT_DLS_FLAG_MANUELL der Firmware). Ein neues
// Bit kommt OHNE NEUE FASSUNG (MANUELL ab b40): eine aeltere App liest den Satz weiter und
// sieht eine gewoehnliche Nacht. Darum verwirft dieser Leser keinen Satz wegen eines Bits,
// das er nicht kennt.
private const val FLAG_GELADEN = 0x01
private const val FLAG_MANUELL = 0x02
// Die Fassungen von NachtDlsRecord, deren Aufbau diese App kennt (NACHT_DLS_VERSION in
// boulder/nacht.h). Eine neue Fassung kommt hier erst mit ihrem Leser dazu - und in die
// Notizen ihres Firmware-Releases die Zeile "Mindest-App" (MindestApp in :pebble).
private val NACHT_FASSUNGEN = setOf(3)
private val PHASEN = 0..4
