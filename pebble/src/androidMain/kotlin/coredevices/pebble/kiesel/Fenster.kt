package coredevices.pebble.kiesel

/**
 * Die Zeitraeume der Seiten: wie weit eine Liste, ein Schnitt, ein Band
 * zurueckreicht, und ab wie vielen Naechten oder Tagen ein Urteil faellt.
 * AN EINEM ORT, weil dieselbe Zahl oft an mehreren Stellen gilt - die 14
 * stand fuenfmal im Code, ohne zu sagen, welche vierzehn Tage gemeint sind.
 *
 * Technische Fristen (Aufbewahren, Nachholen, auf Vorrat lesen) bleiben bei
 * ihrem Besitzer; hier steht, was man auf dem Schirm wiederfindet.
 */
internal object Fenster {
    // --- Gesundheitsseite ---

    /** Die Woche der Gesundheitsseite: heute und die sechs Tage davor (Saeulen, Wasser, HRV). */
    const val WOCHE_TAGE = 7

    /** Hoechstens so viele fruehere Naechte bilden den Schnitt auf "Your usual". */
    const val VERGLEICHSNAECHTE = 7

    /**
     * Ab so vielen frueheren Naechten gibt es ein Urteil ueber den Ruhepuls und
     * einen Schnitt auf "Your usual". Die HRV urteilt nicht hiernach, sondern an
     * ihrem Band - und das braucht [HRV_LERNNAECHTE] Naechte.
     */
    const val LERNNAECHTE = 3

    /** So viele Naechte vor einem Tag bilden sein HRV-Band. */
    const val HRV_NAECHTE = 30

    /** Mit weniger Naechten gibt es kein HRV-Band - es waere Zufall. */
    const val HRV_LERNNAECHTE = 7

    /** So weit zurueck vergleicht die Herzzeile den Puls von heute mit den Tagen davor. */
    const val TAGESHERZ_TAGE = 14L

    /** Ab so vielen frueheren Tagen gibt es ein Urteil ueber den Puls des Tages. */
    const val TAGESHERZ_LERNTAGE = 5

    /** So weit zaehlt der Blutdruck: wer selten misst, misst kaum einmal die Woche. */
    const val BLUTDRUCK_TAGE = 30L

    /**
     * So viele Kalendertage zaehlt die letzte Sitzung fuer die Herzzeile. Neben
     * dem Puls von heute laese sich ein Wert von vor Wochen wie ein heutiger.
     */
    const val BLUTDRUCK_ZEILE_TAGE = 7L

    /** Die Naechte, aus denen die Akkuzeile und die Akkuseite den Verbrauch rechnen. */
    const val AKKU_TAGE = 14L

    // --- Herzgrenzen (Zonen und Kalorien) ---

    /** Der Ruhepuls der Herzgrenzen ist der Median so vieler Naechte - eine Woche. */
    const val HERZGRENZEN_NAECHTE = 7

    /** So weit vor einem Training sucht Boulder diese Naechte; zwei Wochen fangen Luecken auf. */
    const val HERZGRENZEN_TAGE = 14L

    // --- Trends ---

    /**
     * UEBER SO VIELE TAGE rechnen Wochenprofil, Wochenende, Zielquote und
     * Schlafmitte - fest und auf der Karte genannt. Ueber die ganze Geschichte
     * gerechnet mischten sich Jahreszeiten und Quellwechsel ins Urteil, und die
     * Karten widersprachen den Vergleichen, die [VERGLEICH_TAGE] nehmen. Die
     * acht Wochen des Verlaufs und die Richtung (56 Tage) liegen darin.
     */
    const val TREND_TAGE = 90L

    /** Wie viele Wochen der Verlauf zeigt. */
    const val TREND_WOCHEN = 8

    /** Die erste Karte jeder Trendseite: die letzten so vielen Tage ("Last 14 days"). */
    const val LETZTE_TAGE = 14

    /** Ueber so viele Tage vergleicht "What makes a difference". */
    const val VERGLEICH_TAGE = 30L

    // --- Koerper und Sport ---

    /** So weit zurueck zeigt die Koerperseite den Verlauf, und so alt darf ihre Zeile hoechstens sein. */
    const val KOERPER_TAGE = 90L

    /** Die Veraenderung der Koerperzeile: gegen den Wert von vor so vielen Tagen ("in 30 days"). */
    const val KOERPER_VERAENDERUNG_TAGE = 30L

    /** Ein Vierteljahr Trainings auf der Sportseite, wie in Kiesel-Helper. */
    const val SPORT_TAGE = 90L
}
