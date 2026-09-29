package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant

/**
 * Der Akku der Uhr aus dem, was Boulder ohnehin hat.
 *
 * GRAVEL NAHM DIE AKKUSEITE MIT DER TELEMETRIE HERAUS. Die Boulder-Firmware
 * schickt aber mit jeder Nacht den Stand beim Einschlafen und am Ende. Daraus
 * folgt, was die Uhr nachts braucht, tagsueber braucht (vom Ende einer Nacht
 * bis zum Einschlafen der naechsten) und wie lange eine Ladung reicht. Naechte
 * am Kabel und Tage mit Laden dazwischen zaehlen nicht mit.
 *
 * DER MEDIAN, NICHT DER SCHNITT: eine Nacht mit Kieselsport-Training am Abend
 * oder ein Tag mit zwei Stunden Navigation zoege den Schnitt weit weg vom
 * Ueblichen.
 */
internal object Akkurechnung {
    data class Nacht(
        val beginnUtc: Long,
        val einschlafen: Instant,
        val aufwachen: Instant,
        val prozentVon: Int,
        val prozentBis: Int,
        val geladen: Boolean,
    ) {
        val stunden: Double get() = (aufwachen.epochSecond - einschlafen.epochSecond) / 3600.0
        /** Verbrauch je Stunde in der Nacht; null, wo geladen wurde oder nichts zu rechnen ist. */
        val jeStunde: Double? get() = if (geladen || prozentBis > prozentVon || stunden < 1.0) null else (prozentVon - prozentBis) / stunden
    }

    data class Stand(
        val naechte: List<Nacht>,
        val nachtJeStunde: Double?,
        val tagJeStunde: Double?,
        /** Verbrauch in 24 Stunden - acht davon Nacht, wo beides bekannt ist. */
        val jeTag: Double?,
        val tageJeLadung: Double?,
        val tageUebrig: Double?,
        /** Der Verlauf: Sekunde seit 1970, Prozent. */
        val punkte: List<Pair<Long, Int>>,
    )

    fun rechne(entities: List<NachtEntity>, aktuell: Int?, jetzt: Instant): Stand {
        val naechte = entities.filter { it.akkuBeginn != null && it.akkuEnde != null && it.aufwachen > it.einschlafen }
            .sortedBy { it.beginnUtc }
            .map {
                Nacht(
                    it.beginnUtc,
                    Instant.ofEpochSecond(it.beginnUtc + it.einschlafen * 60L),
                    Instant.ofEpochSecond(it.beginnUtc + it.aufwachen * 60L),
                    it.akkuBeginn!!, it.akkuEnde!!, it.geladen,
                )
            }
        val nacht = median(naechte.mapNotNull { it.jeStunde })
        val tage = naechte.zipWithNext().mapNotNull { (a, b) ->
            val stunden = (b.einschlafen.epochSecond - a.aufwachen.epochSecond) / 3600.0
            // Steht der Akku am Abend hoeher als am Morgen, wurde geladen.
            if (a.geladen || b.prozentVon > a.prozentBis || stunden < 1.0 || stunden > 24.0) null
            else (a.prozentBis - b.prozentVon) / stunden
        }
        val tag = median(tage)
        val jeTag = when {
            nacht != null && tag != null -> 8 * nacht + 16 * tag
            nacht != null -> 24 * nacht
            tag != null -> 24 * tag
            else -> null
        }?.takeIf { it > 0 }
        val punkte = buildList {
            naechte.forEach { n ->
                add(n.einschlafen.epochSecond to n.prozentVon)
                add(n.aufwachen.epochSecond to n.prozentBis)
            }
            if (aktuell != null) add(jetzt.epochSecond to aktuell)
        }.sortedBy { it.first }
        return Stand(
            naechte = naechte,
            nachtJeStunde = nacht,
            tagJeStunde = tag,
            jeTag = jeTag,
            tageJeLadung = jeTag?.let { 100 / it },
            tageUebrig = if (jeTag != null && aktuell != null) aktuell / jeTag else null,
            punkte = punkte,
        )
    }

    fun median(werte: List<Double>): Double? {
        if (werte.isEmpty()) return null
        val s = werte.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
