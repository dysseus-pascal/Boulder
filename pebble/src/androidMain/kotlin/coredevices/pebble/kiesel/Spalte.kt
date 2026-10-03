package coredevices.pebble.kiesel

import java.time.LocalDate

/**
 * Die Spalten der Trends: je Groesse eine Reihe von Tageswerten ([Reihen]).
 * Vorher standen die Namen als Text an sieben Stellen, und ein Tippfehler
 * ergab still eine leere Reihe.
 *
 * [schluessel]: der Name in Tagesspeicher und Sicherung. ER BLEIBT, WIE ER
 * IST - das Archiv der letzten Monate und jede Sicherung finden ihre Werte
 * nur unter diesem Namen.
 *
 * [nacht]: ein Wert der Nacht, auf den Tag des Aufwachens gebucht. Eine
 * Nacht ist am Morgen fertig, der Tag selbst erst um Mitternacht - darum
 * zaehlen Nachtspalten bis einschliesslich heute ([grenze]). Vorher hielten
 * Trends und Vergleiche dafuer je eine eigene Liste von Hand gleich.
 *
 * [gruppe]: die Trendseite der Spalte ("herz", "schlaf", "bewegung",
 * "ernaehrung" - so heissen auch die Wege dorthin).
 */
internal enum class Spalte(val schluessel: String, val nacht: Boolean, val gruppe: String) {
    SCHRITTE("schritte", nacht = false, gruppe = "bewegung"),
    /** Aktivminuten aus Schritten; in Kiesel-Helper war es die Trainingsdauer. */
    AKTIV("aktiv", nacht = false, gruppe = "bewegung"),
    SCHLAF("schlaf", nacht = true, gruppe = "schlaf"),
    TIEF("tief", nacht = true, gruppe = "schlaf"),
    /** Die Mitte zwischen Einschlafen und Aufwachen, in Minuten ab 18 Uhr des Vortags. */
    SCHLAF_MITTE("schlaf_mitte", nacht = true, gruppe = "schlaf"),
    /** Das Einschlafen in Minuten ab 18 Uhr des Vortags - fuer "spaet ins Bett". */
    EINSCHLAFEN("einschlafen", nacht = true, gruppe = "schlaf"),
    RUHEPULS("ruhepuls", nacht = true, gruppe = "herz"),
    HRV("hrv", nacht = true, gruppe = "herz"),
    WASSER("wasser", nacht = false, gruppe = "ernaehrung"),
    KOFFEIN("koffein_mg", nacht = false, gruppe = "ernaehrung"),
    PRAEPARATE_FAELLIG("supp_faellig", nacht = false, gruppe = "ernaehrung"),
    PRAEPARATE_GENOMMEN("supp_genommen", nacht = false, gruppe = "ernaehrung"),
    ;

    /** Bis wohin gezaehlt wird (ausschliesslich): Nachtwerte bis heute, Tageswerte nur bis gestern. */
    fun grenze(heute: LocalDate): LocalDate = if (nacht) heute.plusDays(1) else heute

    companion object {
        /** Die Schluessel einiger Spalten - fuer Mengen, die gegen gespeicherte [Reihen] pruefen. */
        fun schluessel(vararg spalten: Spalte): Set<String> = spalten.mapTo(linkedSetOf()) { it.schluessel }
    }
}

/** Die Reihe einer Spalte. Die Reihen bleiben nach [Spalte.schluessel] verwahrt - auch fremde aus Kiesel-Helper. */
internal operator fun <V> Map<String, V>.get(spalte: Spalte): V? = this[spalte.schluessel]

internal operator fun <V> MutableMap<String, V>.set(spalte: Spalte, wert: V) {
    this[spalte.schluessel] = wert
}
