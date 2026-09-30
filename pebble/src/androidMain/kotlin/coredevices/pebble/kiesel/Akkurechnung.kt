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
 * am Kabel und Tage mit Laden dazwischen zaehlen nicht mit - ausser wer tagsueber
 * nur wenig nachlaedt und abends trotzdem tiefer steht: der Tag wirkt dann sparsamer.
 *
 * SUMME DURCH SUMME, NICHT DER MEDIAN JE NACHT: die Uhr meldet ganze Prozent.
 * Eine Nacht von sieben Stunden mit 41 -> 39 % ist 0,28 %/h oder 0,14 oder
 * 0,42 - je nach Rundung. Und eine "Nacht" von einer Stunde ohne Verlust
 * ergab 0 %/h, wurde Median und machte aus 75 % "31 Tage". Ueber alle Stunden
 * zusammen gleicht sich die Rundung aus; Stuecke unter [MINDESTSTUNDEN] zaehlen nicht.
 *
 * OHNE TAGESWERT KEINE RESTZEIT: tagsueber braucht die Uhr ein Vielfaches
 * (Licht, Benachrichtigungen, Puls haeufiger), 24 h zum Nachtwert waeren
 * Wochen, wo es Tage sind.
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
        val jeStunde: Double? get() = if (geladen || prozentBis > prozentVon || stunden < MINDESTSTUNDEN) null else (prozentVon - prozentBis) / stunden
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
        val nacht = rate(naechte.filter { it.jeStunde != null }.map { (it.prozentVon - it.prozentBis) to it.stunden })
        val tage = naechte.zipWithNext().mapNotNull { (a, b) ->
            val stunden = (b.einschlafen.epochSecond - a.aufwachen.epochSecond) / 3600.0
            // Abends hoeher als morgens: geladen. Nach einer Nacht am Kabel ebenso -
            // den Endstand misst die Uhr erst beim Abschluss der Nacht, womoeglich noch am Kabel.
            if (a.geladen || b.prozentVon > a.prozentBis || stunden < MINDESTSTUNDEN || stunden > 24.0) null
            else (a.prozentBis - b.prozentVon) to stunden
        }
        val tag = rate(tage)
        // Fehlt nur die Nacht, rechnet sie wie ein Tag: lieber zu wenig Tage als zu viele.
        val jeTag = tag?.let { 8 * (nacht ?: it) + 16 * it }?.takeIf { it > 0 }
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

    /** Kuerzere Naechte und Tage sagen mit ganzen Prozent nichts. */
    const val MINDESTSTUNDEN = 3.0

    /** Prozent je Stunde aus (Prozent, Stunden)-Stuecken; null ohne Stueck. */
    fun rate(stuecke: List<Pair<Int, Double>>): Double? {
        val stunden = stuecke.sumOf { it.second }
        return if (stunden <= 0) null else stuecke.sumOf { it.first } / stunden
    }
}
