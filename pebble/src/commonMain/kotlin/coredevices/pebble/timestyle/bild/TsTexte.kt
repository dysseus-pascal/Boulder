package coredevices.pebble.timestyle.bild

/*
 * Die Texte der Leiste, Zeichen fuer Zeichen wie die Firmware sie bildet
 * (schritte_text.c, distanz_text.c, zweitzeit.c, util.c, strftime.c). Beide
 * Seiten testen mit denselben Testvektoren (TsTexteTest hier,
 * test_timestyle*.c in der Firmware).
 */
internal object TsTexte {

    /** schritte_text: "999", "5.5k", "12k". */
    fun schritte(schritteIn: Int, trenner: Char): String {
        val s = if (schritteIn < 0) 0 else schritteIn
        return when {
            s < 1000 -> "$s"
            s < 10000 -> "${s / 1000}$trenner${s / 100 % 10}k"
            else -> "${s / 1000}k"
        }
    }

    /**
     * distanz_text: metrisch "57m", ".4km", "4km" (ab 10 km die kleine Schrift),
     * sonst Meilen "3mi" oder ".4mi". Rueckgabe: Text und ob die kleine Schrift gilt.
     */
    fun distanz(meterIn: Int, metrisch: Boolean, trenner: Char): Pair<String, Boolean> {
        var meter = meterIn
        if (metrisch) {
            return when {
                meter < 100 -> "${meter}m" to false
                meter < 1000 -> "$trenner${meter / 100}km" to false
                else -> {
                    meter /= 1000
                    "${meter}km" to (meter > 9)
                }
            }
        }
        val zehntel = meter * 10 / 1609 % 10
        val ganz = (meter + 1609 / 2) / 1609
        return (if (ganz > 0) "${ganz}mi" else "$trenner${zehntel}mi") to false
    }

    /** zweitzeit_text: die Stunde der zweiten Zeitzone ueber UTC, auf die Minute. */
    fun zweitzeit(stunde: Int, minute: Int, gmtoffSekunden: Long, versatzStunden: Int, h24: Boolean, fuehrendeNull: Boolean): String {
        val minutenTag = 24 * 60
        val utc = stunde * 60 + minute - (gmtoffSekunden / 60).toInt()
        var ziel = (utc + versatzStunden * 60) % minutenTag
        if (ziel < 0) ziel += minutenTag
        var h = ziel / 60
        if (h24) return if (fuehrendeNull) h.toString().padStart(2, '0') else h.toString()
        val amPm = if (h < 12) 'a' else 'p'
        h %= 12
        if (h == 0) h = 12
        return (if (fuehrendeNull) h.toString().padStart(2, '0') else h.toString()) + amPm
    }

    /** time_get_beats aus der UTC-Sekunde des Tages: Biel-Zeit = UTC + 1 h. */
    fun beats(utcSekundeDesTages: Int): Int {
        val bmt = ((utcSekundeDesTages + 3600) % 86400 + 86400) % 86400
        return ((bmt.toLong() * 1000 + 43200) / 86400 % 1000).toInt()
    }

    private fun schaltjahr(jahr: Int) = (jahr % 4 == 0 && jahr % 100 != 0) || jahr % 400 == 0

    private fun wochenImJahr(jahr: Int, jahresTag: Int, wochentag: Int, normal: Int, schalt: Int): Int {
        val erster = (((wochentag - jahresTag) % 7) + 7) % 7
        return if (erster == normal || (schaltjahr(jahr) && erster == schalt)) 53 else 52
    }

    /** %V der Firmware (prv_iso8601_week). jahresTag 0-basiert, wochentag 0 = Sonntag. */
    fun isoWoche(jahr: Int, jahresTag: Int, wochentag: Int): Int {
        val basis = (jahresTag + 10 - ((wochentag + 6) % 7)) / 7
        val anpassung = when {
            basis == 0 -> -1
            basis > wochenImJahr(jahr, jahresTag, wochentag, 4, 3) -> 1
            else -> 0
        }
        return when (anpassung) {
            -1 -> wochenImJahr(jahr - 1, jahresTag, wochentag, 5, 6)
            1 -> 1
            else -> basis
        }
    }

    /** Die zweistellige Woche wie strftime "%V" (immer mit Null). */
    fun woche(jahr: Int, jahresTag: Int, wochentag: Int): String = isoWoche(jahr, jahresTag, wochentag).toString().padStart(2, '0')

    /** ClockArea_update_time: die Stunde mit %H/%k/%I/%l (Leerzeichen statt Null). */
    fun stunde(stunde: Int, h24: Boolean, fuehrendeNull: Boolean): String {
        val wert = if (h24) stunde else (if (stunde % 12 == 0) 12 else stunde % 12)
        return if (fuehrendeNull) wert.toString().padStart(2, '0') else wert.toString().padStart(2, ' ')
    }

    /** BatteryMeter_draw: "80%", tuerkisch "%80", gross nur "80". */
    fun akku(prozent: Int, gross: Boolean, sprache: Int): String = when {
        gross -> "$prozent"
        sprache == TsSprachen.TUERKISCH -> "%$prozent"
        else -> "$prozent%"
    }

    /** Temperatur: bei grosser Schrift ohne Gradzeichen. */
    fun temperatur(grad: Int, gross: Boolean): String = if (gross) "$grad" else "$grad°"
}
