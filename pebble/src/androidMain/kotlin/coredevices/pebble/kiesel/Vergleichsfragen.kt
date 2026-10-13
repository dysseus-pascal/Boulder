package coredevices.pebble.kiesel

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * "What makes a difference": zwei Gruppen von Tagen gegeneinander, statt der
 * frueheren Korrelationen ("Links").
 *
 * JEDE FRAGE HAT IHRE ZEITRICHTUNG. Eine Nacht ist auf den Tag des Aufwachens
 * gebucht: ihr Ruhepuls kann nur vom Tag DAVOR beeinflusst sein (t - 1), die
 * Schritte eines Tages nur von der Nacht, die auf ihn gebucht ist (t). Ein
 * Versatz um einen Tag verschoebe jede Karte, und am Geraet faellt das nicht
 * auf - darum steht das hier und nicht im Bildschirm.
 *
 * Keine Frage vergleicht eine Groesse mit sich selbst (Tiefschlaf und HRV
 * entstehen aus derselben Rechnung), und jede steht auf genau einer Seite:
 * der ihrer Zielgroesse. Dieselbe Karte auf zwei Trendseiten sagte nichts
 * Neues und machte beide laenger.
 *
 * Reine Rechnung ohne Android; die Pruefungen stehen in `VergleichsfragenTest`.
 */
internal object Vergleichsfragen {

    /** Spaet ist nach 23 Uhr: 300 min ab 18 Uhr des Vortags, wie die Spalte [Spalte.EINSCHLAFEN]. */
    const val SPAET_AB = 300.0
    /** Ein aktiver Tag erreicht das Aktivziel. */
    val AKTIV_MIN = Gesundheitsziele.AKTIV_MIN.toDouble()
    const val KOFFEIN_VIEL = 200.0

    enum class Art { HRV_KURZ, RUHE_AKTIV, RUHE_SPAET, HRV_SCHRITTE, RUHE_WOCHENENDE, TIEF_KOFFEIN, SCHLAF_AKTIV, SCHRITTE_KURZ, KOFFEIN_KURZ }

    /** Eine Frage: was verglichen wird ([ziel]) und wie die Tage in zwei Gruppen fallen. */
    class Frage(
        val art: Art,
        val ziel: Spalte,
        /** Ist mehr von [ziel] gut? Entscheidet die Farbe der Wirkung. */
        val mehrIstGut: Boolean,
        /** Naechte statt Tage im Text. */
        val naechte: Boolean,
        val mitKoffein: Boolean = false,
        /** Fuer den Tag des Zielwerts: Gruppe A (true), B (false) oder keine (null). */
        val zuordnung: (Reihen, Gesundheitsziele.Ziele) -> (LocalDate) -> Boolean?,
    ) {
        val gruppe: String get() = ziel.gruppe
    }

    private fun wert(d: Reihen, spalte: Spalte): Map<LocalDate, Double> = d[spalte].orEmpty().toMap()

    private fun wochenende(t: LocalDate) = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

    // "Very short" ist, was Health "Very short night" nennt ([Gesundheitsziele.Ziele.sehrKurzMin]) - "short"
    // heisst dort schon die Stunde darueber. Viele Schritte sind das Schrittziel. Beide aus "Goals" (z).
    val FRAGEN: List<Frage> = listOf(
        // Dieselbe Nacht: sehr kurz geschlafen, und die HRV dieser Nacht.
        Frage(Art.HRV_KURZ, Spalte.HRV, true, true) { d, z ->
            val s = wert(d, Spalte.SCHLAF); { t -> s[t]?.let { it < z.sehrKurzMin } }
        },
        // Der Tag davor bewegt, und der Ruhepuls der Nacht danach.
        Frage(Art.RUHE_AKTIV, Spalte.RUHEPULS, false, true) { d, _ ->
            val a = wert(d, Spalte.AKTIV); { t -> a[t.minusDays(1)]?.let { it >= AKTIV_MIN } }
        },
        Frage(Art.RUHE_SPAET, Spalte.RUHEPULS, false, true) { d, _ ->
            val e = wert(d, Spalte.EINSCHLAFEN); { t -> e[t]?.let { it > SPAET_AB } }
        },
        Frage(Art.HRV_SCHRITTE, Spalte.HRV, true, true) { d, z ->
            val s = wert(d, Spalte.SCHRITTE); { t -> s[t.minusDays(1)]?.let { it >= z.schritte } }
        },
        Frage(Art.RUHE_WOCHENENDE, Spalte.RUHEPULS, false, true) { _, _ ->
            { t -> wochenende(t) }
        },
        // Der Kaffee des Vortags, und der Tiefschlaf der Nacht danach.
        Frage(Art.TIEF_KOFFEIN, Spalte.TIEF, true, true, mitKoffein = true) { d, _ ->
            val k = wert(d, Spalte.KOFFEIN); { t -> k[t.minusDays(1)]?.let { it >= KOFFEIN_VIEL } }
        },
        Frage(Art.SCHLAF_AKTIV, Spalte.SCHLAF, true, true) { d, _ ->
            val a = wert(d, Spalte.AKTIV); { t -> a[t.minusDays(1)]?.let { it >= AKTIV_MIN } }
        },
        // Ein Tag nach sehr kurzer Nacht: wie viel man sich dann bewegt.
        Frage(Art.SCHRITTE_KURZ, Spalte.SCHRITTE, true, false) { d, z ->
            val s = wert(d, Spalte.SCHLAF); { t -> s[t]?.let { it < z.sehrKurzMin } }
        },
        Frage(Art.KOFFEIN_KURZ, Spalte.KOFFEIN, false, false, mitKoffein = true) { d, z ->
            val s = wert(d, Spalte.SCHLAF); { t -> s[t]?.let { it < z.sehrKurzMin } }
        },
    )

    /** Die Fragen einer Trendseite; die mit Koffein nur, wenn Drinktervall protokolliert. */
    fun fuer(gruppe: String, mitKoffein: Boolean): List<Frage> = FRAGEN.filter { it.gruppe == gruppe && (mitKoffein || !it.mitKoffein) }

    fun rechne(
        d: Reihen, gruppe: String, heute: LocalDate, mitKoffein: Boolean, ziele: Gesundheitsziele.Ziele,
    ): List<Pair<Frage, Auswertung.Gruppenvergleich>> =
        // Bis wohin gezaehlt wird, sagt die Zielspalte: Nachtwerte bis heute, Tageswerte nur bis gestern.
        fuer(gruppe, mitKoffein).map { f -> f to Auswertung.vergleich(d[f.ziel].orEmpty(), f.ziel.grenze(heute), f.zuordnung(d, ziele)) }

    /**
     * Ob eine Frage schon Wochenende gegen Werktag fuer [spalte] vergleicht.
     * Dann sagt die Wochenkarte es nicht noch einmal: ihr Mittel ueber 90 Tage
     * und der strengere Vergleich ueber 30 standen sonst als "Weekends 52,
     * weekdays 55" neben "no difference" auf derselben Seite.
     */
    fun vergleichtWochenende(spalte: Spalte): Boolean = FRAGEN.any { it.art == Art.RUHE_WOCHENENDE && it.ziel == spalte }
}
