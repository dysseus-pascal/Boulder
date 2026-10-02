package coredevices.pebble.kiesel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Ein Wert aus der Akte zu seinem Zeitpunkt - kg, %, kcal am Tag oder m, je nach Art. */
internal data class Koerpermessung(val zeit: Instant, val wert: Double)

/** Der Wert eines Tages: der letzte, den eine Waage an dem Tag schrieb. */
internal data class Koerpertag(val tag: LocalDate, val zeit: Instant, val wert: Double)

/** Was die Akte ueber den Koerper weiss, roh: kg, Fett in %, kcal am Tag, m - die Einheit der Anzeige kommt spaeter. */
internal data class Koerperstand(
    val gewicht: List<Koerpermessung> = emptyList(),
    val fett: List<Koerpermessung> = emptyList(),
    val magermasse: List<Koerpermessung> = emptyList(),
    val knochen: List<Koerpermessung> = emptyList(),
    val wasser: List<Koerpermessung> = emptyList(),
    val grundumsatz: List<Koerpermessung> = emptyList(),
    val groesse: Koerpermessung? = null,
) {
    val leer: Boolean
        get() = gewicht.isEmpty() && fett.isEmpty() && magermasse.isEmpty() && knochen.isEmpty() &&
            wasser.isEmpty() && grundumsatz.isEmpty() && groesse == null
}

internal data class Koerperzeile(
    /** Das letzte Gewicht - ohne Gewicht das letzte Fett. */
    val wert: Koerpertag,
    val istGewicht: Boolean,
    /** Das Fett neben dem Gewicht, aus der Woche um dessen Tag; null, wenn [wert] selbst das Fett ist. */
    val fett: Koerpertag?,
    val veraenderung: Double?,
    /** So alt, dass das Datum der Messung dazugehoert. */
    val alt: Boolean,
)

internal data class Zusammensetzung(
    val fett: Koerpertag? = null,
    val magermasse: Koerpertag? = null,
    val knochen: Koerpertag? = null,
    val wasser: Koerpertag? = null,
    val wasserProzent: Int? = null,
    val grundumsatz: Koerpertag? = null,
    /** Mit Tag und Zeit des Gewichts, aus dem er stammt. */
    val bmi: Koerpertag? = null,
    val groesse: Koerpermessung? = null,
) {
    val leer: Boolean
        get() = listOf(fett, magermasse, knochen, wasser, grundumsatz, bmi).all { it == null } && groesse == null
}

/** Eine runde y-Achse: Linien von [von] bis [bis] im Abstand [schritt]. */
internal data class Achse(val von: Double, val bis: Double, val schritt: Double) {
    // Gerundet auf Zehntel: 3 * 0.1 ist in Double 0.30000000000000004.
    val linien: List<Double>
        get() = (0..((bis - von) / schritt).roundToInt()).map { Math.round((von + it * schritt) * 10) / 10.0 }
}

/** Die Rechnungen der Koerperseite, ohne Android. */
internal object Koerperrechnung {
    /** So weit zurueck zeigt die Seite den Verlauf, und so alt darf die Zeile hoechstens sein. */
    const val FENSTER_TAGE = 90L
    const val VERAENDERUNG_TAGE = 30L
    /** Wie weit der Vergleichstag vom Ziel abweichen darf - wer sich wiegt, tut es selten taeglich. */
    const val SPIELRAUM_TAGE = 7L
    /** Der gleitende Median nimmt so viele Tage nach jeder Seite: eine Woche in Summe. */
    const val MEDIAN_HALB = 3L
    /** Ab dieser Luecke bricht die Linie - zwischen zwei so fernen Wiegungen weiss niemand den Verlauf. */
    const val LUECKE_TAGE = 14L
    const val PFUND_JE_KG = Formate.PFUND_JE_KG

    /**
     * Ein Wert je Tag, aeltester zuerst. WAAGEN SCHREIBEN MEHRFACH, oft auch
     * ueber eine zweite App - es gilt der letzte Satz des Tages.
     */
    fun tage(messungen: List<Koerpermessung>, zone: ZoneId): List<Koerpertag> = messungen
        .groupBy { it.zeit.atZone(zone).toLocalDate() }
        .map { (tag, l) -> l.maxBy { it.zeit }.let { Koerpertag(tag, it.zeit, it.wert) } }
        .sortedBy { it.tag }

    /**
     * Letzter Tageswert minus dem um heute-30: der naechste Tag davor, sonst danach, im Spielraum.
     * Ist der letzte Wert selbst aelter als der Spielraum, stimmte "in 30 days" nicht mehr.
     */
    fun veraenderung(tage: List<Koerpertag>, heute: LocalDate): Double? {
        val letzter = tage.lastOrNull() ?: return null
        if (letzter.tag < heute.minusDays(SPIELRAUM_TAGE)) return null
        val ziel = heute.minusDays(VERAENDERUNG_TAGE)
        val bezug = tage.lastOrNull { it.tag <= ziel && it.tag >= ziel.minusDays(SPIELRAUM_TAGE) }
            ?: tage.firstOrNull { it.tag > ziel && it.tag <= ziel.plusDays(SPIELRAUM_TAGE) }
            ?: return null
        if (bezug.tag >= letzter.tag) return null
        return letzter.wert - bezug.wert
    }

    fun zeile(stand: Koerperstand, heute: LocalDate, zone: ZoneId): Koerperzeile? {
        val grenze = heute.minusDays(FENSTER_TAGE)
        val gewicht = tage(stand.gewicht, zone)
        val fett = tage(stand.fett, zone)
        val g = gewicht.lastOrNull()?.takeIf { it.tag >= grenze }
        val f = fett.lastOrNull()?.takeIf { it.tag >= grenze }
        val wert = g ?: f ?: return null
        return Koerperzeile(
            wert = wert,
            istGewicht = g != null,
            // Die Zeile datiert das Fett nicht: eines von vor Monaten wirkte neben dem heutigen Gewicht wie heute gemessen.
            fett = g?.let { gw -> fett.lastOrNull { abs(ChronoUnit.DAYS.between(gw.tag, it.tag)) <= SPIELRAUM_TAGE } },
            veraenderung = veraenderung(if (g != null) gewicht else fett, heute),
            alt = wert.tag < heute.minusDays(SPIELRAUM_TAGE),
        )
    }

    fun zusammensetzung(stand: Koerperstand, zone: ZoneId): Zusammensetzung {
        val gewicht = tage(stand.gewicht, zone)
        val letztesGewicht = gewicht.lastOrNull()
        val wasser = tage(stand.wasser, zone).lastOrNull()
        return Zusammensetzung(
            fett = tage(stand.fett, zone).lastOrNull(),
            magermasse = tage(stand.magermasse, zone).lastOrNull(),
            knochen = tage(stand.knochen, zone).lastOrNull(),
            wasser = wasser,
            // Nur mit dem Gewicht desselben Tages: ein Anteil an einem anderen Gewicht waere geraten.
            wasserProzent = wasser?.let { w -> gewicht.firstOrNull { it.tag == w.tag }?.takeIf { it.wert > 0 }?.let { (w.wert / it.wert * 100).roundToInt() } },
            grundumsatz = tage(stand.grundumsatz, zone).lastOrNull(),
            bmi = letztesGewicht?.let { g -> stand.groesse?.let { bmi(g.wert, it.wert) }?.let { Koerpertag(g.tag, g.zeit, it) } },
            groesse = stand.groesse,
        )
    }

    fun bmi(kg: Double, meter: Double): Double? {
        if (kg <= 0 || meter <= 0) return null
        return Math.round(kg / (meter * meter) * 10) / 10.0
    }

    /**
     * Gleitender Median je gemessenem Tag, in Stuecken getrennt an Luecken. Median, weil ein
     * Ausreisser (Waage mit Schuhen, ein Kind darauf) eine Mittellinie verbiegen wuerde.
     */
    fun medianlinie(tage: List<Koerpertag>, halb: Long = MEDIAN_HALB, luecke: Long = LUECKE_TAGE): List<List<Pair<LocalDate, Double>>> {
        val aus = mutableListOf<MutableList<Pair<LocalDate, Double>>>()
        var vorher: LocalDate? = null
        tage.forEach { t ->
            val w = tage.filter { it.tag >= t.tag.minusDays(halb) && it.tag <= t.tag.plusDays(halb) }.map { it.wert }.sorted()
            val median = if (w.size % 2 == 1) w[w.size / 2] else (w[w.size / 2 - 1] + w[w.size / 2]) / 2
            if (vorher == null || ChronoUnit.DAYS.between(vorher, t.tag) > luecke) aus += mutableListOf<Pair<LocalDate, Double>>()
            aus.last() += t.tag to median
            vorher = t.tag
        }
        return aus
    }

    /** Runde Schritte, hoechstens [felder] Felder; [mindestSpanne], sonst saehe man Zehntel als Berge. */
    fun achse(tief: Double, hoch: Double, mindestSpanne: Double = 1.0, felder: Int = 3): Achse {
        val mitte = (tief + hoch) / 2
        val halb = maxOf(hoch - tief, mindestSpanne) / 2
        val unten = mitte - halb
        val oben = mitte + halb
        for (schritt in listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)) {
            // Der kleine Zuschlag faengt Rundungsfehler: 77.5 / 0.5 ist nicht immer genau 155.
            val von = floor(unten / schritt + 1e-9) * schritt
            val bis = ceil(oben / schritt - 1e-9) * schritt
            if ((bis - von) / schritt <= felder + 1e-9) return Achse(von, bis, schritt)
        }
        return Achse(floor(unten), ceil(oben), ceil(oben) - floor(unten))
    }

    fun masse(kg: Double, pfund: Boolean): Double = if (pfund) kg * PFUND_JE_KG else kg

    /** Die Groesse in Fuss und Zoll, auf ganze Zoll gerundet: 1.80 m -> 5' 11". */
    fun fussZoll(meter: Double): Pair<Int, Int> {
        val zoll = (meter / 0.0254).roundToInt()
        return zoll / 12 to zoll % 12
    }

    @Deprecated("Formate.zahl(x, 1)", ReplaceWith("Formate.zahl(x, 1, locale)"))
    fun eineStelle(x: Double, locale: Locale = Locale.getDefault()): String = Formate.zahl(x, 1, locale)

    /** Minus typografisch (U+2212); was auf 0.0 rundet, bekommt "±" - es hat keine Richtung. */
    @Deprecated("Formate.vorzeichen(x, 1)", ReplaceWith("Formate.vorzeichen(x, 1, locale)"))
    fun mitVorzeichen(x: Double, locale: Locale = Locale.getDefault()): String = Formate.vorzeichen(x, 1, locale)
}
