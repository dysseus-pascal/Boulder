package io.rebble.libpebblecommon.database.dao

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = Logger.withTag("HealthDaoExt")

// Boulder: Lesen und Schreiben eines Pakets am Stueck - der Empfang startet je Paket
// eine Coroutine, und zwei Pakete mit derselben Minute saehen sonst beide "nichts da".
private val schreibSperre = Mutex()

/** Boulder: so weit reicht eine Bereichsabfrage hoechstens, ein Tag = 1440 Minutenzeilen. */
internal const val BEREICH_SEKUNDEN = 86_400L

/**
 * Inserts health data with priority based on step count. If data already exists for a timestamp,
 * only replaces it if the new data has more steps.
 *
 * Boulder: je Paket eine Bereichsabfrage und ein Insert (eine Transaktion) statt
 * je Minute - vorher ~2880 Room-Aufrufe am Tag.
 */
suspend fun HealthDao.insertHealthDataWithPriority(data: List<HealthDataEntity>) {
    if (data.isEmpty()) return
    schreibSperre.withLock {
        val bestand = HashMap<Long, HealthDataEntity>()
        for ((von, bis) in zeitbereiche(data.map { it.timestamp })) {
            getHealthDataForRange(von, bis).associateByTo(bestand) { it.timestamp }
        }
        val wahl = vorrang(data, bestand, { it.timestamp }, { it.steps.toLong() })
        if (wahl.zuSchreiben.isNotEmpty()) insertHealthData(wahl.zuSchreiben)
        logger.d { "Health data insert complete: ${wahl.zusammenfassung("higher steps", "lower/equal steps")}" }
    }
}

/**
 * Inserts overlay data (sleep, activities) while preventing duplicates.
 * An overlay entry is considered duplicate if it has the same startTime and type.
 * When duplicates exist, keeps the entry with the longer duration to prevent data loss.
 *
 * Boulder: wie oben eine Bereichsabfrage und ein Insert je Paket.
 */
suspend fun HealthDao.insertOverlayDataWithDeduplication(data: List<OverlayDataEntity>) {
    if (data.isEmpty()) return
    schreibSperre.withLock {
        val typen = data.map { it.type }.distinct()
        val bestand = HashMap<Pair<Long, Int>, OverlayDataEntity>()
        for ((von, bis) in zeitbereiche(data.map { it.startTime })) {
            getOverlayEntries(von, bis, typen).associateByTo(bestand) { it.startTime to it.type }
        }
        val wahl = vorrang(data, bestand, { it.startTime to it.type }, { it.duration })
        if (wahl.zuSchreiben.isNotEmpty()) insertOverlayData(wahl.zuSchreiben)
        logger.d { "Overlay data insert complete: ${wahl.zusammenfassung("longer duration", "shorter/equal duration")}" }
    }
}

internal data class Vorrangwahl<T>(
    val zuSchreiben: List<T>,
    val neu: Int,
    val ersetzt: Int,
    val uebersprungen: Int,
) {
    fun zusammenfassung(besser: String, schlechter: String): String =
        "$neu new, $ersetzt replaced ($besser), $uebersprungen skipped ($schlechter)"
}

/**
 * Boulder: die bisherige Regel gegen eine Karte statt je Satz gegen die Datenbank.
 * Jeder Gewinner kommt sofort in die Karte, weil sich Saetze desselben Pakets mit
 * gleichem Schluessel wie vorher der Reihe nach vergleichen.
 */
internal fun <K, T> vorrang(
    neue: List<T>,
    bestand: MutableMap<K, T>,
    schluessel: (T) -> K,
    mass: (T) -> Long,
): Vorrangwahl<T> {
    val gewinner = LinkedHashMap<K, T>()
    var neu = 0
    var ersetzt = 0
    var uebersprungen = 0
    for (satz in neue) {
        val k = schluessel(satz)
        val alt = bestand[k]
        when {
            alt == null -> neu++
            mass(satz) > mass(alt) -> ersetzt++
            else -> {
                uebersprungen++
                continue
            }
        }
        bestand[k] = satz
        gewinner[k] = satz
    }
    return Vorrangwahl(gewinner.values.toList(), neu, ersetzt, uebersprungen)
}

/** Boulder: Bereiche [von, bis) von hoechstens einem Tag, auch fuer einen Nachholschub ueber Tage. */
internal fun zeitbereiche(zeiten: List<Long>): List<Pair<Long, Long>> {
    if (zeiten.isEmpty()) return emptyList()
    val sortiert = zeiten.sorted()
    val bereiche = mutableListOf<Pair<Long, Long>>()
    var von = sortiert.first()
    var letzte = von
    for (t in sortiert) {
        if (t - von >= BEREICH_SEKUNDEN) {
            bereiche += von to letzte + 1
            von = t
        }
        letzte = t
    }
    bereiche += von to letzte + 1
    return bereiche
}
