package coredevices.pebble.kiesel

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormateTest {
    private val f = Formate
    private val us = Locale.US
    private val de = Locale.GERMANY

    @Test
    fun zahlenMitTrennernUndEchtemMinus() {
        assertEquals("9,960", f.zahl(9960L, us))
        assertEquals("9.960", f.zahl(9960L, de))
        assertEquals("−1,234", f.zahl(-1234L, us))
        assertEquals("1,234.6", f.zahl(1234.56, 1, us))
        assertEquals("−3", f.zahl(-3.2, 0, us))
        // Was auf null rundet, steht ohne Zeichen - "−0" laese sich als Abnahme.
        assertEquals("0", f.zahl(-0.3, 0, us))
        assertEquals("0.0", f.zahl(-0.04, 1, us))
    }

    @Test
    fun zahlenInSprachenMitEigenenZiffern() {
        // Devanagari: die Null-Pruefung kannte nur '1'..'9' und strich jedem Wert das Minus.
        val ne = Locale.forLanguageTag("ne")
        assertEquals("−३", f.zahl(-3.2, 0, ne))
        assertEquals("०", f.zahl(-0.2, 0, ne))
        assertEquals("−१,२३४", f.zahl(-1234L, ne))
        // Persisch: anderes Komma - ".0" faellt nach der Zahl weg, nicht nach dem Text.
        val fa = Locale.forLanguageTag("fa")
        assertEquals("۱۰k", f.schritteKurz(10_040L, fa))
        assertEquals("۹٫۹k", f.schritteKurz(9960L, fa))
    }

    @Test
    fun vorzeichenImmerUndNullOhneRichtung() {
        assertEquals("+2", f.vorzeichen(2.4, locale = us))
        assertEquals("−3", f.vorzeichen(-2.6, locale = us))
        assertEquals("±0", f.vorzeichen(-0.4, locale = us))
        assertEquals("−0.6", f.vorzeichen(-0.6, 1, us))
        assertEquals("−0,6", f.vorzeichen(-0.6, 1, de))
        assertEquals("+1.2", f.vorzeichen(1.23, 1, us))
        assertEquals("±0.0", f.vorzeichen(-0.04, 1, us))
        assertEquals("+1,250", f.vorzeichen(1250.0, locale = us))
    }

    @Test
    fun schritteKurzWerdenAbgerundet() {
        assertEquals("400", f.schritteKurz(400L, us))
        assertEquals("999", f.schritteKurz(999.6, us))
        assertEquals("4.9k", f.schritteKurz(4986.0, us))
        assertEquals("1k", f.schritteKurz(1000L, us))
        // 9'960 unter dem Ziel ist nicht "10k".
        assertEquals("9.9k", f.schritteKurz(9960L, us))
        assertEquals("10k", f.schritteKurz(10_040L, us))
        assertEquals("12.3k", f.schritteKurz(12_345L, us))
        assertEquals("4,9k", f.schritteKurz(4940L, de))
    }

    @Test
    fun prozentOhneAbstand() {
        assertEquals("55%", f.prozent(55, us))
        assertEquals("55%", f.prozent(0.553, us))
        assertEquals("−4%", f.prozent(-4, us))
        // Getrennt fuer Kennzahl und Zeile - und auch dort ohne Abstand.
        assertEquals(Formate.Angabe("97", "%"), f.prozentTeile(97, us))
        assertEquals("97%", f.prozentTeile(97, us).text)
        assertEquals("", f.abstandVor("%"))
        assertEquals("1.2%/h", Formate.Angabe("1.2", "%/h").text)
        assertEquals(" ", f.abstandVor("bpm"))
    }

    @Test
    fun mengenGanzMitEinheit() {
        assertEquals("95 mg", f.menge(94.6, "mg", us).text)
        assertEquals(Formate.Angabe("1,250", "ml"), f.menge(1249.5, "ml", us))
        assertEquals("45 s", f.menge(45L, "s", us).text)
        // Ohne Einheit nur die Zahl, und was auf null rundet, ohne Zeichen.
        assertEquals("0", f.menge(-0.3, null, us).text)
    }

    @Test
    fun literUndSchaetzung() {
        assertEquals("1.2", f.liter(1240, us))
        assertEquals("0", f.liter(0, us))
        assertEquals("≈ 194", f.ungefaehr("194"))
        // Ohne Wert nur der Strich, nie "≈ –".
        assertEquals("–", f.ungefaehr(null))
    }

    @Test
    fun literFeinFuerEinzelneGlaeser() {
        // Ein Glas ist 0.25 l, nicht "0.3" wie mit einer Stelle; ganze Liter ohne Nullen.
        assertEquals("0.25", f.literFein(250, us))
        assertEquals("0.5", f.literFein(500, us))
        assertEquals("1", f.literFein(1000, us))
        assertEquals("1.25", f.literFein(1250, us))
        assertEquals("0", f.literFein(0, us))
        assertEquals("0,33", f.literFein(333, de))
    }

    @Test
    fun dauerInEinerForm() {
        assertEquals("45 min", f.dauer(45))
        assertEquals("1:05 h", f.dauer(65))
        assertEquals(Formate.Angabe("8:20", "h"), f.dauerTeile(500))
        assertEquals(Formate.Angabe("0", "min"), f.dauerTeile(0))
        assertEquals("8:05", f.stundenMinuten(485))
        assertEquals("4:05", f.minutenSekunden(245))
        // Unterschiede einer Dauer tragen eine Richtung, null keine.
        assertEquals(Formate.Angabe("+23", "min"), f.dauerUnterschied(23.4))
        assertEquals("−1:05 h", f.dauerUnterschied(-65.0).text)
        assertEquals("±0 min", f.dauerUnterschied(-0.4).text)
    }

    @Test
    fun distanzTempoUndGeschwindigkeitMetrischUndImperial() {
        assertEquals("12.3 km", f.distanz(12_345.0, false, us).text)
        assertEquals(Formate.Angabe("7.7", "mi"), f.distanz(12_345.0, true, us))
        assertEquals(Formate.Angabe("5:12", "/km"), f.tempo(312, false))
        // 312 s/km sind 502 s/mi.
        assertEquals(Formate.Angabe("8:22", "/mi"), f.tempo(312, true))
        assertEquals(Formate.Angabe("16.2", "km/h"), f.geschwindigkeit(16.2, false, us))
        assertEquals(Formate.Angabe("10.1", "mph"), f.geschwindigkeit(16.2, true, us))
    }

    @Test
    fun ohneStreckeKeinTempoStattUnendlich() {
        assertNull(f.tempo(0.0, 600, false))
        assertNull(f.geschwindigkeit(5000.0, 0, false))
        assertEquals(Formate.Angabe("5:00", "/km"), f.tempo(2000.0, 600, false))
        assertEquals(Formate.Angabe("12.0", "km/h"), f.geschwindigkeit(2000.0, 600, false, us))
    }

    @Test
    fun hoeheGewichtUndGroesse() {
        assertEquals(Formate.Angabe("600", "m"), f.hoehe(600.0, false, us))
        assertEquals(Formate.Angabe("1,969", "ft"), f.hoehe(600.0, true, us))
        assertEquals(Formate.Angabe("1,200", "m/h"), f.steigrate(1200.0, false, us))
        assertEquals(Formate.Angabe("78.4", "kg"), f.gewicht(78.4, false, us))
        assertEquals(Formate.Angabe("172.8", "lb"), f.gewicht(78.4, true, us))
        assertEquals(Formate.Angabe("180", "cm"), f.groesse(1.80, false))
        assertEquals(Formate.Angabe("5′ 11″", null), f.groesse(1.80, true))
        assertEquals("5′ 11″", f.groesse(1.80, true).text)
    }

    @Test
    fun reineUmrechnungenFuerAchsen() {
        assertEquals(1.0, f.streckeIn(1000.0, false), 1e-9)
        assertEquals(1.0, f.streckeIn(1609.344, true), 1e-9)
        assertEquals(1000.0, f.hoeheIn(304.8, true), 1e-9)
        assertEquals(10.0, f.geschwindigkeitIn(16.09344, true), 1e-9)
        assertEquals(502.0, f.tempoIn(312.0, true), 0.5)
        assertEquals(172.8, f.gewichtIn(78.4, true), 0.05)
        assertEquals("mi", f.streckeEinheit(true))
        assertEquals("ft", f.hoeheEinheit(true))
        assertEquals("mph", f.geschwindigkeitEinheit(true))
        assertEquals("/km", f.tempoEinheit(false))
        assertEquals("lb", f.gewichtEinheit(true))
        // Die Texte nehmen dieselbe Rechnung: Achse und Zahl daneben koennen nicht auseinanderlaufen.
        assertEquals(String.format(us, "%.1f", f.streckeIn(12_345.0, true)), f.distanz(12_345.0, true, us).zahl)
    }

    @Test
    fun heuteGesternSonstDatum() {
        val heute = LocalDate.of(2026, 10, 2)
        assertEquals(Formate.Tagesbezug.HEUTE, f.tagesbezug(heute, heute))
        assertEquals(Formate.Tagesbezug.GESTERN, f.tagesbezug(heute.minusDays(1), heute))
        assertEquals(Formate.Tagesbezug.ANDERER, f.tagesbezug(heute.minusDays(2), heute))
        assertEquals(Formate.Tagesbezug.ANDERER, f.tagesbezug(heute.plusDays(1), heute))
    }

    @Test
    fun uhrzeitUndStundeNachWahlDesTelefons() {
        val t = LocalTime.of(21, 14)
        // Die Muster, wie Android sie fuer en-US und de liefert (zeitMuster): die fuehrende Null
        // und das schmale Leerzeichen vor PM kommen aus dem Muster, wie bei DateFormat.getTimeFormat.
        assertEquals("21:14", f.uhrzeit(t, "HH:mm", de))
        assertEquals("09:14", f.uhrzeit(LocalTime.of(9, 14), "HH:mm", de))
        assertEquals("9:14 PM", f.uhrzeit(t, "h:mm a", us))
        assertEquals("9:14\u202FPM", f.uhrzeit(t, "h:mm\u202Fa", us))
        assertEquals("12:00 AM", f.uhrzeit(LocalTime.MIDNIGHT, "h:mm a", us))
        assertEquals("21", f.stunde(t, true, us))
        assertEquals("9 PM", f.stunde(t, false, us))
        assertEquals("0", f.stunde(LocalTime.MIDNIGHT, true, us))
    }

    @Test
    fun datumKurzInDerReihenfolgeDerSpracheUndJahrNurWennAnders() {
        val tag = LocalDate.of(2026, 9, 28)
        assertEquals("Sep 28", f.datumKurz(tag, "MMM d", us))
        assertEquals("28 Sep", f.datumKurz(tag, "d MMM", us))
        val heute = LocalDate.of(2026, 10, 2)
        assertEquals("Sep 28", f.datumKurz(tag, "MMM d", us, heute, "MMM d, y"))
        assertEquals("Sep 28, 2025", f.datumKurz(tag.minusYears(1), "MMM d", us, heute, "MMM d, y"))
    }

    @Test
    fun wochentagInZweiBuchstaben() {
        assertEquals("Mo", f.wochentagKurz(DayOfWeek.MONDAY, us))
        assertEquals("Th", f.wochentagKurz(DayOfWeek.THURSDAY, us))
        assertEquals("Di", f.wochentagKurz(DayOfWeek.TUESDAY, de))
        assertEquals("Sunday", f.wochentagLang(DayOfWeek.SUNDAY, us))
        // Vor Datum oder Uhrzeit im Text drei Buchstaben: "Mon, 7:53 AM".
        assertEquals("Mon", f.wochentag(DayOfWeek.MONDAY, us))
        assertEquals("Sep", f.monatKurz(java.time.Month.SEPTEMBER, us))
        assertEquals("Wed, Sep 30", f.datumKurz(LocalDate.of(2026, 9, 30), "EEE, MMM d", us))
    }

    @Test
    fun gemerkteFormatiererSchreibenWieFrischGebaute() {
        // Je Muster und Sprache einmal gebaut: derselbe Text wie mit einem neuen Formatierer, auch beim Wechsel.
        val muster = listOf("HH:mm", "h:mm a", "h:mm a", "H:mm")
        val sprachen = listOf(us, de, Locale.FRANCE, Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("ar-EG"))
        for (m in muster) for (l in sprachen) for (minute in 0 until 1440 step 37) {
            val t = LocalTime.of(minute / 60, minute % 60)
            val neu = java.text.SimpleDateFormat(m, l).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t.toSecondOfDay() * 1000L))
            assertEquals(neu, f.uhrzeit(t, m, l), "$m $l $t")
            assertEquals(t.format(java.time.format.DateTimeFormatter.ofPattern("H", l)), f.stunde(t, true, l))
            assertEquals(t.format(java.time.format.DateTimeFormatter.ofPattern("h a", l)), f.stunde(t, false, l))
        }
        val tag = LocalDate.of(2025, 12, 31)
        for (l in sprachen) {
            assertEquals(tag.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", l)), f.datumKurz(tag, "d MMM", l))
            assertEquals(tag.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, y", l)), f.datumKurz(tag, "MMM d", l, LocalDate.of(2026, 1, 1), "MMM d, y"))
        }
    }

    @Test
    fun uhrzeitAusVielenThreadsZugleich() {
        // SimpleDateFormat rechnet in einem eigenen Kalender: geteilt zwischen Threads vertauschte es Zeiten.
        val fehler = java.util.concurrent.atomic.AtomicInteger()
        val faeden = (0 until 8).map { n ->
            Thread {
                repeat(5_000) { i ->
                    val t = LocalTime.of((n * 3 + i) % 24, i % 60)
                    if (f.uhrzeit(t, "HH:mm", de) != String.format("%02d:%02d", t.hour, t.minute)) fehler.incrementAndGet()
                }
            }.also { it.start() }
        }
        faeden.forEach { it.join() }
        assertEquals(0, fehler.get())
    }
}
