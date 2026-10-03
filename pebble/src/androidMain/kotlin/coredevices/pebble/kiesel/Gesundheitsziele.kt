package coredevices.pebble.kiesel

/**
 * Die Ziele und Grenzwerte, an denen Seiten, Trends, Vergleiche und Widget
 * messen. AN EINEM ORT, weil jede Stelle dasselbe Ziel meint: stand das
 * Schrittziel viermal im Code, haette eine Aenderung an einer Stelle drei
 * verschiedene Ziele auf den Schirm gebracht.
 *
 * Keines ist einstellbar; die Uhr kennt sie nicht (ausser dem Wasser, siehe
 * [Drinktervall]).
 */
internal object Gesundheitsziele {
    /** Schritte am Tag: die uebliche Zehntausend - Saeulen, Trendlinie, Widgetbalken und "many steps" im Vergleich. */
    const val SCHRITTE = 10_000L

    /**
     * Schlaf je Nacht in Minuten: acht Stunden. Eine Stunde darunter beginnt
     * die "very short night" ([Vergleichsfragen.SEHR_KURZ_MIN]).
     */
    const val SCHLAF_MIN = 8 * 60

    /** Aktive Minuten am Tag: die halbe Stunde der WHO - Widgetkachel und der "active day" der Vergleiche. */
    const val AKTIV_MIN = 30

    /** Weniger Wachminuten in einer Nacht gelten als durchgeschlafen - kurzes Aufwachen merkt niemand. */
    const val DURCHGESCHLAFEN_MIN = 10

    /** Glaeser Wasser am Tag, solange Drinktervall kein eigenes Ziel gemeldet hat - die Vorgabe der Uhr-App. */
    const val WASSER_GLAESER = 8

    /** Milliliter je Glas, ebenso die Vorgabe von Drinktervall. */
    const val GLAS_ML = 300
}
