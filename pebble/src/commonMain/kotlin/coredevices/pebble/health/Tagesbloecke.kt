package coredevices.pebble.health

/** Ein Tag: hoechstens so viele Minutenzeilen (1440) liegen je Block im Speicher. */
internal const val BLOCK_SEKUNDEN = 86_400L

/**
 * Boulder: die Zeilen nach [nach] bis [letzte] in Tagesbloecken, aelteste zuerst.
 * Ab Cursor 0 waeren es sonst 90 Tage = 129 600 Zeilen auf einmal. Gibt
 * [verarbeite] false zurueck, endet der Lauf; der naechste beginnt mit diesem Block.
 */
internal suspend fun <T> inTagesbloecken(
    nach: Long,
    letzte: Long,
    hatZeilen: suspend (von: Long, bis: Long) -> Boolean,
    lies: suspend (von: Long, bis: Long) -> List<T>,
    verarbeite: suspend (List<T>) -> Boolean,
) {
    val ende = letzte + 1
    var von: Long? = nach + 1
    while (von != null && von < ende) {
        val bis = von + BLOCK_SEKUNDEN
        val zeilen = lies(von, bis)
        if (zeilen.isNotEmpty() && !verarbeite(zeilen)) return
        von = if (zeilen.isEmpty()) ersterTagMitZeilen(bis, ende, hatZeilen) else bis
    }
}

/**
 * Beginn des Tagesfensters ab [von] mit der ersten Zeile vor [ende], oder null.
 * Halbiert mit [hatZeilen], weil ab Cursor 0 ~20 000 leere Tage davor liegen.
 */
internal suspend fun ersterTagMitZeilen(
    von: Long,
    ende: Long,
    hatZeilen: suspend (von: Long, bis: Long) -> Boolean,
): Long? {
    if (von >= ende || !hatZeilen(von, ende)) return null
    var unten = von
    var oben = ende
    while (oben - unten > BLOCK_SEKUNDEN) {
        val mitte = unten + (oben - unten) / 2
        if (hatZeilen(unten, mitte)) oben = mitte else unten = mitte
    }
    return unten
}
