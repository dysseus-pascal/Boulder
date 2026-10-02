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

    /**
     * Unter sieben Stunden - was Health "Very short night" nennt (eine Stunde
     * unter dem Schlafziel). "Short night" heisst dort schon 7 bis 8 h; die
     * Fragen sagen darum "very short", damit Wort und Grenze dieselben sind.
     */
    const val SEHR_KURZ_MIN = SCHLAFZIEL_MIN - 60
    /** Spaet ist nach 23 Uhr: 300 min ab 18 Uhr des Vortags, wie die Reihe "einschlafen". */
    const val SPAET_AB = 300.0
    const val AKTIV_MIN = 30.0
    val SCHRITTE_VIEL = SCHRITTZIEL.toDouble()
    const val KOFFEIN_VIEL = 200.0

    enum class Art { HRV_KURZ, RUHE_AKTIV, RUHE_SPAET, HRV_SCHRITTE, RUHE_WOCHENENDE, TIEF_KOFFEIN, SCHLAF_AKTIV, SCHRITTE_KURZ, KOFFEIN_KURZ }

    /** Eine Frage: was verglichen wird ([ziel]) und wie die Tage in zwei Gruppen fallen. */
    class Frage(
        val art: Art,
        val ziel: String,
        /** Ist mehr von [ziel] gut? Entscheidet die Farbe der Wirkung. */
        val mehrIstGut: Boolean,
        /** Naechte statt Tage im Text. */
        val naechte: Boolean,
        val mitKoffein: Boolean = false,
        /** Fuer den Tag des Zielwerts: Gruppe A (true), B (false) oder keine (null). */
        val zuordnung: (Reihen) -> (LocalDate) -> Boolean?,
    ) {
        val gruppe: String get() = gruppeVon(ziel)
    }

    /** Die Trendseite einer Reihe. */
    fun gruppeVon(spalte: String): String = when (spalte) {
        "hrv", "ruhepuls" -> "herz"
        "schlaf", "tief", "schlaf_mitte", "einschlafen" -> "schlaf"
        "wasser", "koffein_mg", "supp_faellig", "supp_genommen" -> "ernaehrung"
        else -> "bewegung"
    }

    private fun wert(d: Reihen, spalte: String): Map<LocalDate, Double> = d[spalte].orEmpty().toMap()

    private fun wochenende(t: LocalDate) = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

    val FRAGEN: List<Frage> = listOf(
        // Dieselbe Nacht: sehr kurz geschlafen, und die HRV dieser Nacht.
        Frage(Art.HRV_KURZ, "hrv", true, true) { d ->
            val s = wert(d, "schlaf"); { t -> s[t]?.let { it < SEHR_KURZ_MIN } }
        },
        // Der Tag davor bewegt, und der Ruhepuls der Nacht danach.
        Frage(Art.RUHE_AKTIV, "ruhepuls", false, true) { d ->
            val a = wert(d, "aktiv"); { t -> a[t.minusDays(1)]?.let { it >= AKTIV_MIN } }
        },
        Frage(Art.RUHE_SPAET, "ruhepuls", false, true) { d ->
            val e = wert(d, "einschlafen"); { t -> e[t]?.let { it > SPAET_AB } }
        },
        Frage(Art.HRV_SCHRITTE, "hrv", true, true) { d ->
            val s = wert(d, "schritte"); { t -> s[t.minusDays(1)]?.let { it >= SCHRITTE_VIEL } }
        },
        Frage(Art.RUHE_WOCHENENDE, "ruhepuls", false, true) { _ ->
            { t -> wochenende(t) }
        },
        // Der Kaffee des Vortags, und der Tiefschlaf der Nacht danach.
        Frage(Art.TIEF_KOFFEIN, "tief", true, true, mitKoffein = true) { d ->
            val k = wert(d, "koffein_mg"); { t -> k[t.minusDays(1)]?.let { it >= KOFFEIN_VIEL } }
        },
        Frage(Art.SCHLAF_AKTIV, "schlaf", true, true) { d ->
            val a = wert(d, "aktiv"); { t -> a[t.minusDays(1)]?.let { it >= AKTIV_MIN } }
        },
        // Ein Tag nach sehr kurzer Nacht: wie viel man sich dann bewegt.
        Frage(Art.SCHRITTE_KURZ, "schritte", true, false) { d ->
            val s = wert(d, "schlaf"); { t -> s[t]?.let { it < SEHR_KURZ_MIN } }
        },
        Frage(Art.KOFFEIN_KURZ, "koffein_mg", false, false, mitKoffein = true) { d ->
            val s = wert(d, "schlaf"); { t -> s[t]?.let { it < SEHR_KURZ_MIN } }
        },
    )

    private val NACHTZIELE = setOf("schlaf", "tief", "ruhepuls", "hrv")

    /** Bis wohin gezaehlt wird: Nachtwerte bis heute, Tageswerte nur bis gestern. */
    fun grenze(ziel: String, heute: LocalDate): LocalDate = if (ziel in NACHTZIELE) heute.plusDays(1) else heute

    /** Die Fragen einer Trendseite; die mit Koffein nur, wenn Drinktervall protokolliert. */
    fun fuer(gruppe: String, mitKoffein: Boolean): List<Frage> = FRAGEN.filter { it.gruppe == gruppe && (mitKoffein || !it.mitKoffein) }

    fun rechne(d: Reihen, gruppe: String, heute: LocalDate, mitKoffein: Boolean): List<Pair<Frage, Auswertung.Gruppenvergleich>> =
        fuer(gruppe, mitKoffein).map { f -> f to Auswertung.vergleich(d[f.ziel].orEmpty(), grenze(f.ziel, heute), f.zuordnung(d)) }

    /**
     * Ob eine Frage schon Wochenende gegen Werktag fuer [spalte] vergleicht.
     * Dann sagt die Wochenkarte es nicht noch einmal: ihr Mittel ueber 90 Tage
     * und der strengere Vergleich ueber 30 standen sonst als "Weekends 52,
     * weekdays 55" neben "no difference" auf derselben Seite.
     */
    fun vergleichtWochenende(spalte: String): Boolean = FRAGEN.any { it.art == Art.RUHE_WOCHENENDE && it.ziel == spalte }
}
