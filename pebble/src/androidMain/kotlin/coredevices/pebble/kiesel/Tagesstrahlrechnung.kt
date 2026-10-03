package coredevices.pebble.kiesel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Was der Tagesstrahl der Gesundheitsseite zeigt, ohne Oberflaeche: welche
 * Ereignisse von heute, in welcher Reihenfolge und was davon eine Zeile ist.
 * Farben, Titel und Mengen legt die Ansicht darum - hier steht nur, was ein
 * Test ohne Schirm pruefen kann.
 */
internal object Tagesstrahlrechnung {

    /** Ein Ereignis des Tages auf dem Strahl. */
    sealed class Ereignis {
        abstract val zeit: Instant

        data class Training(val training: TrainingEintrag) : Ereignis() {
            override val zeit: Instant get() = training.sitzung.startTime
        }

        /** Ein Glas von Drinktervall ([Glaseintrag.eigen]) oder Wasser einer anderen App. */
        data class Wasser(val glas: Glaseintrag) : Ereignis() {
            override val zeit: Instant get() = glas.zeit
        }

        data class Getraenk(val eintrag: Tageseintrag) : Ereignis() {
            override val zeit: Instant get() = eintrag.zeit
        }

        /**
         * Was in derselben Minute mit derselben Herkunft eingetragen wurde -
         * SupCycle hakt mehrere Praeparate in einem Zug ab. Fremde Eintraege
         * derselben Minute bilden eine eigene Gruppe: sonst loeschte die
         * Zeile nur einen Teil ihres Titels.
         */
        data class Praeparate(val eintraege: List<Tageseintrag>) : Ereignis() {
            override val zeit: Instant get() = eintraege.first().zeit
            val eigen: Boolean get() = eintraege.first().eigen
            val titel: String get() = eintraege.joinToString(", ") { it.name }
        }
    }

    /**
     * Die Ereignisse von [heute], die frueheste zuerst. Getraenke nur mit
     * Drinktervall, Praeparate nur mit SupCycle auf der Uhr (UhrApps), wie
     * ueberall. Bei gleicher Zeit bleibt die Reihenfolge Training, Wasser,
     * Getraenk, Praeparat - die Sortierung ist stabil.
     */
    fun ereignisse(stand: GesundheitsStand, trainings: List<TrainingEintrag>, heute: LocalDate, zone: ZoneId): List<Ereignis> = buildList {
        // Die Trainings kommen aus mehreren Tagen der Akte; der Strahl zeigt nur die, die heute begannen.
        trainings.filter { it.sitzung.startTime.atZone(zone).toLocalDate() == heute }.forEach { add(Ereignis.Training(it)) }
        if (stand.mitDrinktervall) {
            stand.glasEintraege.forEach { add(Ereignis.Wasser(it)) }
            stand.eintraege.filter { !it.praeparat }.forEach { add(Ereignis.Getraenk(it)) }
        }
        if (stand.mitSupCycle) {
            // Nach Minute und Herkunft: ein Zug von SupCycle ist eine Zeile, ein fremder Eintrag daneben eine eigene.
            stand.eintraege.filter { it.praeparat }.groupBy { it.zeit.epochSecond / 60 to it.eigen }.values.forEach { add(Ereignis.Praeparate(it)) }
        }
    }.sortedBy { it.zeit }
}
