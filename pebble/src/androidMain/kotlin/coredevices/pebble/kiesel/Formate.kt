package coredevices.pebble.kiesel

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import coredevices.pebble.R
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Wie Zahlen, Zeiten und Einheiten in Boulder aussehen - EINE Stelle fuer die
 * ganze App. Vorher stand derselbe Tag als "Sep 28" und "28 Sep" auf einer
 * Seite, 9'960 Schritte als "10.0k" unter der Ziellinie, und das Minus war
 * mal ASCII, mal U+2212.
 *
 * Der Kern ist rein (Locale, Uhrzeit- und Datumsmuster werden uebergeben)
 * und ohne Android pruefbar (FormateTest). Die Varianten mit [Context] holen
 * nur, was das Telefon dazu weiss (Stundenwahl, Reihenfolge der Sprache),
 * und reichen an denselben Kern weiter - beide Wege ergeben denselben Text.
 *
 * Die Regeln:
 * - Fehlt ein Wert, steht [FEHLT] ("–") - nie "≈ –", nie eine erfundene 0.
 * - Negative Zahlen tragen [MINUS] (U+2212), Unterschiede immer ein Vorzeichen.
 * - Prozent ohne Abstand: "55%" (wie der Akku im Satz, Nutzerentscheid) -
 *   auch getrennt als [Angabe] und in zahlMitEinheit.
 * - Schritte kurz werden ABGERUNDET: "9.9k" unter dem Ziel, nie "10k".
 * - Einheiten folgen Gravels Schalter "Imperial Units" (healthSettings.imperialUnits).
 * - Zahl und Einheit kommen getrennt ([Angabe]), damit die Ansicht die
 *   Einheit klein und grau setzen kann (zahlMitEinheit).
 */
internal object Formate {

    /** Der Strich fuer einen fehlenden Wert. */
    const val FEHLT = "–"

    /** Das typografische Minus: das ASCII-"-" ist schmaler als "+" und rutscht in Spalten. */
    const val MINUS = "−"

    /**
     * Was zwischen Zahl und [einheit] steht: nichts vor Prozent ("55%",
     * "1.2%/h"), sonst ein Leerzeichen ("128 bpm") - fuer zahlMitEinheit.
     */
    fun abstandVor(einheit: String): String = if (einheit.startsWith("%")) "" else " "

    /** Eine Zahl mit ihrer Einheit, getrennt; [text] fuer Fliesstext ("12.3 km", "55%"). */
    data class Angabe(val zahl: String, val einheit: String?) {
        val text: String get() = if (einheit == null) zahl else zahl + abstandVor(einheit) + einheit
    }

    // --- Zahlen -------------------------------------------------------------------

    /** Eine ganze Zahl mit Tausendertrennern der Sprache: "9,960", negativ mit echtem Minus. */
    fun zahl(n: Long, locale: Locale = Locale.getDefault()): String = mitMinus(String.format(locale, "%,d", n))

    /** Eine Kommazahl mit [nachkomma] Stellen und Tausendertrennern: "1,234.5". */
    fun zahl(x: Double, nachkomma: Int = 0, locale: Locale = Locale.getDefault()): String {
        val muster = "%,.${nachkomma}f"
        val s = String.format(locale, muster, x)
        // "-0" liest niemand gern: was auf null rundet, steht ohne Zeichen. Character.digit
        // kennt auch Devanagari oder Arabisch-Indisch - mit '1'..'9' galt dort jede Zahl als null.
        return if (s.none { Character.digit(it, 10) > 0 }) String.format(locale, muster, 0.0) else mitMinus(s)
    }

    /**
     * Ein Unterschied mit Vorzeichen: "+2", "−3", "−0.6". Was auf null rundet,
     * bekommt "±" - es hat keine Richtung, und "−0" laese sich als Abnahme.
     */
    fun vorzeichen(x: Double, nachkomma: Int = 0, locale: Locale = Locale.getDefault()): String {
        val faktor = Math.pow(10.0, nachkomma.toDouble())
        val gerundet = (x * faktor).roundToLong() / faktor
        val betrag = String.format(locale, "%,.${nachkomma}f", abs(gerundet))
        return when {
            gerundet > 0 -> "+$betrag"
            gerundet < 0 -> "$MINUS$betrag"
            else -> "±$betrag"
        }
    }

    /**
     * Schritte kurz, ueber Saeulen und an Linien: "400", "4.9k", "10k".
     * ABGERUNDET, NICHT GERUNDET: 9'960 als "10k" ueber einer Saeule unter dem
     * Ziel behauptete das Ziel. "9.9k" stimmt.
     */
    fun schritteKurz(schritte: Double, locale: Locale = Locale.getDefault()): String {
        val ganz = floor(schritte)
        if (ganz < 1000) return String.format(locale, "%.0f", ganz)
        // Auf Hunderter abrunden; ob ".0" wegfaellt, entscheidet die Zahl, nicht der Text -
        // das Komma ist je Sprache ein anderes Zeichen.
        val hunderter = floor(schritte / 100).toLong()
        return if (hunderter % 10 == 0L) String.format(locale, "%d", hunderter / 10) + "k"
        else String.format(locale, "%.1f", hunderter / 10.0) + "k"
    }

    /** Dasselbe fuer gezaehlte Schritte. */
    fun schritteKurz(schritte: Long, locale: Locale = Locale.getDefault()): String = schritteKurz(schritte.toDouble(), locale)

    /** Prozent getrennt, fuer Kennzahl, Zeile und Hauptwert: Angabe("55", "%") - steht als "55%". */
    fun prozentTeile(p: Int, locale: Locale = Locale.getDefault()): Angabe = Angabe(zahl(p.toLong(), locale), "%")

    /** Prozent ohne Abstand, fuer Saetze: "55%". */
    fun prozent(p: Int, locale: Locale = Locale.getDefault()): String = prozentTeile(p, locale).text

    /** Ein Anteil 0..1 als ganze Prozent: 0.553 -> "55%". */
    fun prozent(anteil: Double, locale: Locale = Locale.getDefault()): String = prozent((anteil * 100).roundToInt(), locale)

    /** Liter mit einer Stelle ohne Einheit: "1.2"; nichts getrunken ist "0", nicht "0.0". */
    fun liter(ml: Int, locale: Locale = Locale.getDefault()): String =
        if (ml <= 0) String.format(locale, "%d", 0) else String.format(locale, "%.1f", ml / 1000.0)

    /** Eine Schaetzung: "≈ 194" - ohne Wert nur der Strich, "≈ –" las sich wie ein Darstellungsfehler. */
    fun ungefaehr(text: String?): String = text?.let { "≈ $it" } ?: FEHLT

    // --- Dauer --------------------------------------------------------------------

    /** Eine Dauer mit Einheit: ab einer Stunde "1:23 h", darunter "45 min". */
    fun dauer(minuten: Long): String = dauerTeile(minuten).text

    /** Dieselbe Dauer getrennt, fuer die fette Zahl mit kleiner Einheit. */
    fun dauerTeile(minuten: Long): Angabe =
        if (minuten >= 60) Angabe(stundenMinuten(minuten.toInt()), "h") else Angabe("$minuten", "min")

    /**
     * Ein Unterschied einer Dauer mit Richtung, wie [dauerTeile] getrennt:
     * "+23 min", "−1:05 h", "±0 min" (die Vergleiche: "Sleep after late
     * caffeine"). Gerundet wird vor dem Zeichen - "−0" gibt es nicht.
     */
    fun dauerUnterschied(minuten: Double): Angabe {
        val m = minuten.roundToLong()
        val zeichen = when {
            m > 0 -> "+"
            m < 0 -> MINUS
            else -> "±"
        }
        val teile = dauerTeile(abs(m))
        return teile.copy(zahl = zeichen + teile.zahl)
    }

    /** Eine Dauer als "8:20" - ohne Einheit, die steht daneben oder ergibt sich (Saeulen). */
    fun stundenMinuten(minuten: Int): String = String.format(Locale.ROOT, "%d:%02d", minuten / 60, minuten % 60)

    /** Minuten und Sekunden: "4:05" - fuer Kilometer, Saetze und Tempo, die selten eine Stunde dauern. */
    fun minutenSekunden(sekunden: Long): String = String.format(Locale.ROOT, "%d:%02d", sekunden / 60, sekunden % 60)

    // --- Einheiten (Gravels "Imperial Units") -----------------------------------------

    /** Meter je Meile (international). */
    const val METER_JE_MEILE = 1609.344

    /** Meter je Fuss (international). */
    const val METER_JE_FUSS = 0.3048

    /** Pfund je Kilogramm (avoirdupois). */
    const val PFUND_JE_KG = 2.2046226218

    // Die reinen Umrechnungen fuer Achsen und Rechnungen: ein Bild in mi braucht
    // die Zahl, nicht den Text. Die Text-Funktionen darunter nehmen dieselben.

    /** Meter als Zahl der Streckeneinheit: km oder mi. */
    fun streckeIn(meter: Double, imperial: Boolean): Double = if (imperial) meter / METER_JE_MEILE else meter / 1000

    /** Die Streckeneinheit: "km" oder "mi". */
    fun streckeEinheit(imperial: Boolean): String = if (imperial) "mi" else "km"

    /** Meter als Zahl der Hoeheneinheit: m oder ft. */
    fun hoeheIn(meter: Double, imperial: Boolean): Double = if (imperial) meter / METER_JE_FUSS else meter

    /** Die Hoeheneinheit: "m" oder "ft". */
    fun hoeheEinheit(imperial: Boolean): String = if (imperial) "ft" else "m"

    /** km/h als Zahl der Geschwindigkeitseinheit: km/h oder mph. */
    fun geschwindigkeitIn(kmh: Double, imperial: Boolean): Double = if (imperial) kmh * 1000 / METER_JE_MEILE else kmh

    /** Die Geschwindigkeitseinheit: "km/h" oder "mph". */
    fun geschwindigkeitEinheit(imperial: Boolean): String = if (imperial) "mph" else "km/h"

    /** Sekunden je Kilometer als Sekunden je Streckeneinheit (km oder mi) - fuer Tempoachsen. */
    fun tempoIn(sekundenJeKm: Double, imperial: Boolean): Double = if (imperial) sekundenJeKm * METER_JE_MEILE / 1000 else sekundenJeKm

    /** Die Tempoeinheit: "/km" oder "/mi". */
    fun tempoEinheit(imperial: Boolean): String = if (imperial) "/mi" else "/km"

    /** Kilogramm als Zahl der Gewichtseinheit: kg oder lb. */
    fun gewichtIn(kg: Double, imperial: Boolean): Double = if (imperial) kg * PFUND_JE_KG else kg

    /** Die Gewichtseinheit: "kg" oder "lb". */
    fun gewichtEinheit(imperial: Boolean): String = if (imperial) "lb" else "kg"

    /** Eine Strecke: "12.3 km" oder "7.6 mi". */
    fun distanz(meter: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): Angabe =
        Angabe(String.format(locale, "%.1f", streckeIn(meter, imperial)), streckeEinheit(imperial))

    /** Ein Tempo aus Sekunden je Kilometer: "5:12 /km" oder "8:22 /mi". */
    fun tempo(sekundenJeKm: Long, imperial: Boolean): Angabe =
        Angabe(minutenSekunden(tempoIn(sekundenJeKm.toDouble(), imperial).roundToLong()), tempoEinheit(imperial))

    /** Das Tempo aus Strecke und Zeit ([Sportrechnung.tempo]); null, wo es keins gibt - nie "Infinity". */
    fun tempo(meter: Double, sekunden: Long, imperial: Boolean): Angabe? =
        Sportrechnung.tempo(meter, sekunden)?.let { tempo(it, imperial) }

    /** Eine Geschwindigkeit: "16.2 km/h" oder "10.1 mph". */
    fun geschwindigkeit(kmh: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): Angabe =
        Angabe(String.format(locale, "%.1f", geschwindigkeitIn(kmh, imperial)), geschwindigkeitEinheit(imperial))

    /** Die Geschwindigkeit aus Strecke und Zeit ([Sportrechnung.kmh]); null ohne Strecke oder Zeit. */
    fun geschwindigkeit(meter: Double, sekunden: Long, imperial: Boolean, locale: Locale = Locale.getDefault()): Angabe? =
        Sportrechnung.kmh(meter, sekunden)?.let { geschwindigkeit(it, imperial, locale) }

    /** Eine Hoehe oder ein Aufstieg: "600 m" oder "1,969 ft". */
    fun hoehe(meter: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): Angabe =
        Angabe(zahl(hoeheIn(meter, imperial), 0, locale), hoeheEinheit(imperial))

    /** Aufstieg je Stunde beim Wandern: "420 m/h" oder "1,378 ft/h". */
    fun steigrate(meterJeStunde: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): Angabe =
        hoehe(meterJeStunde, imperial, locale).let { it.copy(einheit = it.einheit + "/h") }

    /** Ein Gewicht mit einer Stelle: "78.4 kg" oder "172.8 lb". */
    fun gewicht(kg: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): Angabe =
        Angabe(String.format(locale, "%.1f", gewichtIn(kg, imperial)), gewichtEinheit(imperial))

    /** Eine Koerpergroesse: "180 cm" oder "5′ 11″" (ganze Zoll, die Einheit steckt in den Strichen). */
    fun groesse(meter: Double, imperial: Boolean): Angabe {
        if (!imperial) return Angabe("${(meter * 100).roundToInt()}", "cm")
        val (fuss, zoll) = Koerperrechnung.fussZoll(meter)
        return Angabe("$fuss′ $zoll″", null)
    }

    // --- Tage und Uhrzeiten (rein) -------------------------------------------------------

    /** Wie ein Tag zu heute steht - daraus wird "Today", "Yesterday" oder das Datum. */
    enum class Tagesbezug { HEUTE, GESTERN, ANDERER }

    /** Ob [tag] heute, gestern oder ein anderer Tag ist - ein morgiger Tag zaehlt als anderer. */
    fun tagesbezug(tag: LocalDate, heute: LocalDate): Tagesbezug = when (tag) {
        heute -> Tagesbezug.HEUTE
        heute.minusDays(1) -> Tagesbezug.GESTERN
        else -> Tagesbezug.ANDERER
    }

    /**
     * Eine Uhrzeit nach [muster] (vom Telefon: [zeitMuster]) - "21:14",
     * "09:14" oder "9:14 PM". Ueber SimpleDateFormat wie Androids
     * DateFormat.getTimeFormat: so setzt die reine Variante dasselbe Zeichen
     * vor AM/PM und dieselbe fuehrende Null wie die mit [Context].
     */
    fun uhrzeit(zeit: LocalTime, muster: String, locale: Locale = Locale.getDefault()): String {
        val format = SimpleDateFormat(muster, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.format(Date(zeit.toSecondOfDay() * 1000L))
    }

    /** Eine volle Stunde fuer Zeitachsen: "21" oder "9 PM" - knapper als die Uhrzeit, aber in derselben Stundenwahl. */
    fun stunde(zeit: LocalTime, vierundzwanzig: Boolean, locale: Locale = Locale.getDefault()): String =
        zeit.format(DateTimeFormatter.ofPattern(if (vierundzwanzig) "H" else "h a", locale))

    /**
     * Ein Tag kurz nach [muster] ("MMM d" oder "d MMM", vom Telefon:
     * [datumMuster]). Mit [heute]: das Jahr nur, wenn es ein anderes ist -
     * dafuer [musterMitJahr].
     */
    fun datumKurz(
        tag: LocalDate, muster: String, locale: Locale = Locale.getDefault(),
        heute: LocalDate? = null, musterMitJahr: String? = null,
    ): String {
        val jahrMuster = musterMitJahr?.takeIf { heute != null && tag.year != heute.year }
        return tag.format(DateTimeFormatter.ofPattern(jahrMuster ?: muster, locale))
    }

    /** Der Wochentag in zwei Buchstaben: "Mo", "Tu" - EINE Regel fuer jede Reihe von sieben Tagen (Saeulen, Kalender). */
    fun wochentagKurz(tag: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        tag.getDisplayName(TextStyle.SHORT, locale).take(2)

    /** Der Wochentag abgekuerzt, wie im Text vor Datum oder Uhrzeit: "Mon" ("Mon, 7:53 AM"). */
    fun wochentag(tag: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        tag.getDisplayName(TextStyle.SHORT, locale)

    /** Der Wochentag ausgeschrieben - fuer Listen und TalkBack. */
    fun wochentagLang(tag: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        tag.getDisplayName(TextStyle.FULL, locale)

    /** Der Monat abgekuerzt fuer Achsen: "Sep". */
    fun monatKurz(monat: Month, locale: Locale = Locale.getDefault()): String =
        monat.getDisplayName(TextStyle.SHORT, locale)

    // --- Mit Context: was das Telefon dazu weiss --------------------------------------

    /**
     * Das Muster fuer einen Tag in der Reihenfolge der Sprache: "MMM d" oder
     * "d MMM", mit [mitJahr] "MMM d, y", mit [mitWochentag] "EEE, MMM d".
     */
    fun datumMuster(locale: Locale = Locale.getDefault(), mitJahr: Boolean = false, mitWochentag: Boolean = false): String =
        DateFormat.getBestDateTimePattern(locale, (if (mitWochentag) "EEE" else "") + (if (mitJahr) "y" else "") + "MMMd")

    /**
     * Das Muster einer Uhrzeit, wie Android es fuer Sprache und Stundenwahl
     * bildet - dasselbe wie DateFormat.getTimeFormat: "h:mm a" oder "HH:mm".
     */
    fun zeitMuster(vierundzwanzig: Boolean, locale: Locale = Locale.getDefault()): String =
        DateFormat.getBestDateTimePattern(locale, if (vierundzwanzig) "Hm" else "hm")

    /** Ein Tag kurz: "Sep 28" - in Listen, unter Saeulen, auf Achsen. */
    fun datumKurz(tag: LocalDate): String {
        val l = Locale.getDefault()
        return datumKurz(tag, datumMuster(l), l)
    }

    /** Ein Tag als Titel oder Messtag: "Sep 28", mit Jahr nur, wenn es nicht dieses ist ("Sep 28, 2025"). */
    fun datum(tag: LocalDate, heute: LocalDate = LocalDate.now()): String {
        val l = Locale.getDefault()
        return datumKurz(tag, datumMuster(l), l, heute, datumMuster(l, mitJahr = true))
    }

    /** Ein Tag mit Wochentag fuer Seitenkoepfe und Filter: "Wed, Sep 30", mit Jahr nur, wenn es nicht dieses ist. */
    fun datumMitWochentag(tag: LocalDate, heute: LocalDate = LocalDate.now()): String {
        val l = Locale.getDefault()
        return datumKurz(tag, datumMuster(l, mitWochentag = true), l, heute, datumMuster(l, mitJahr = true, mitWochentag = true))
    }

    /** Eine Uhrzeit im Format des Telefons - mit seiner Wahl zwischen 12 und 24 Stunden. */
    fun uhrzeit(context: Context, zeit: LocalTime): String = uhrzeit(zeit, zeitMuster(DateFormat.is24HourFormat(context)))

    /** Ein Zeitpunkt als Uhrzeit im Format des Telefons: "9:14 PM" oder "21:14". */
    fun uhrzeit(context: Context, t: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        uhrzeit(context, t.atZone(zone).toLocalTime())

    /** Eine volle Stunde fuer Zeitachsen, im Format des Telefons: "21" oder "9 PM". */
    fun stunde(context: Context, zeit: LocalTime): String = stunde(zeit, DateFormat.is24HourFormat(context))

    /**
     * Ein Tag in EINER Form: "Today", "Yesterday", sonst das Datum.
     * [klein] fuer die Mitte eines Satzes ("today").
     */
    fun tag(context: Context, tag: LocalDate, heute: LocalDate = LocalDate.now(), klein: Boolean = false): String =
        when (tagesbezug(tag, heute)) {
            Tagesbezug.HEUTE -> context.getString(if (klein) R.string.ks_fmt_heute_klein else R.string.ks_fmt_heute)
            Tagesbezug.GESTERN -> context.getString(if (klein) R.string.ks_fmt_gestern_klein else R.string.ks_fmt_gestern)
            Tagesbezug.ANDERER -> datum(tag, heute)
        }

    /**
     * Ein Zeitpunkt in EINER Form: "Today, 9:14 PM", "Yesterday, 9:14 PM",
     * "Sep 27, 9:14 PM". Mit [mitWochentag] traegt ein anderer Tag seinen
     * Wochentag ("Sun, Sep 27, 9:14 PM") - fuer Listen ohne Tageskopf. Nur in
     * einem Zeitstrahl von heute [heuteOhneTag]: dort waere "Today" Laerm.
     */
    fun zeitpunkt(
        context: Context, t: Instant, jetzt: Instant = Instant.now(), klein: Boolean = false,
        heuteOhneTag: Boolean = false, mitWochentag: Boolean = false, zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val tag = t.atZone(zone).toLocalDate()
        val heute = jetzt.atZone(zone).toLocalDate()
        val zeit = uhrzeit(context, t, zone)
        if (heuteOhneTag && tag == heute) return zeit
        val vorn = if (mitWochentag && tagesbezug(tag, heute) == Tagesbezug.ANDERER) datumMitWochentag(tag, heute)
        else tag(context, tag, heute, klein)
        return context.getString(R.string.ks_fmt_tag_zeit, vorn, zeit)
    }

    /**
     * Wochentag und Uhrzeit ohne "Today"/"Yesterday": "Mon, 7:53 AM", mit
     * [mitDatum] "Sun, Sep 27, 8:16 AM" - wo die Woche schon im Kopf steht
     * (Trainingsliste) oder ein fester Zeitpunkt gemeint ist (Trainingsdetail).
     */
    fun wochentagZeit(
        context: Context, t: Instant, mitDatum: Boolean, jetzt: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val tag = t.atZone(zone).toLocalDate()
        val vorn = if (mitDatum) datumMitWochentag(tag, jetzt.atZone(zone).toLocalDate()) else wochentag(tag.dayOfWeek)
        return context.getString(R.string.ks_fmt_tag_zeit, vorn, uhrzeit(context, t, zone))
    }

    /** Ein Zeitraum von [von] bis [bis] (beide eingeschlossen) ohne Jahr: "Sep 21 – 27" - der Kopf einer vergangenen Woche. */
    fun zeitraum(context: Context, von: LocalDate, bis: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String =
        DateUtils.formatDateRange(
            context, von.atStartOfDay(zone).toInstant().toEpochMilli(), bis.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )

    /**
     * Wie lange her: "just now" unter einer Minute (DateUtils sagte dort "0
     * minutes ago"), sonst "5 minutes ago", "Yesterday", "Sep 27".
     */
    fun relativ(context: Context, millis: Long, jetzt: Long = System.currentTimeMillis()): String =
        if (jetzt - millis < DateUtils.MINUTE_IN_MILLIS) context.getString(R.string.ks_fmt_eben)
        else DateUtils.getRelativeTimeSpanString(millis, jetzt, DateUtils.MINUTE_IN_MILLIS).toString()

    // --- Intern -------------------------------------------------------------------------

    /** Das ASCII-Minus vor der ersten Ziffer wird U+2212; manche Sprachen setzen ein Richtungszeichen davor. */
    private fun mitMinus(s: String): String {
        val i = s.indexOf('-')
        return if (i >= 0 && s.take(i).none { it.isDigit() }) s.replaceRange(i, i + 1, MINUS) else s
    }
}
