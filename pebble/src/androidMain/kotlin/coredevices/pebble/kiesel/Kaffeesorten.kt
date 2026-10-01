package coredevices.pebble.kiesel

/**
 * Was ein Kaffee aus Drinktervall in die Akte bringt: Koffein, kcal, Zucker.
 *
 * DIE NUMMERN KOMMEN VON DER UHR (CoffeeKind in Drinktervalls src/c/coffee.h)
 * und sind fest: 0 Espresso, 1 Kaffee, 2 Latte macchiato, 3 Energy-Drink.
 * Milch und Zucker stehen als Bits ueber der Sorte (COFFEE_KIND).
 *
 * Die Werte sind Durchschnitte fuer eine gewoehnliche Portion - genauer kann
 * eine Uhr es nicht wissen, und fuer die Tageskurve des Koffeins reicht es.
 */
internal object Kaffeesorten {
    const val ESPRESSO = 0
    const val KAFFEE = 1
    const val LATTE_MACCHIATO = 2
    const val ENERGY = 3
    /** Ein eigenes Getraenk aus Drinktervalls Einstellungen: Werte kommen von der Uhr. */
    const val EIGEN = 4

    private const val MILCH = 0x10
    private const val ZUCKER = 0x20

    /** Ein Beutel Zucker, 4 g. */
    private const val ZUCKER_G = 4.0
    private const val KCAL_JE_G_ZUCKER = 4.0
    /** Ein Schuss Vollmilch in den Kaffee, 30 ml. */
    private const val MILCH_KCAL = 19.0

    data class Portion(val sorte: Int, val milch: Boolean, val zucker: Boolean, val koffeinMg: Double, val kcal: Double, val zuckerG: Double)

    /** Die Portion zu einem COFFEE_KIND der Uhr - null fuer eine Sorte, die es nicht gibt. */
    fun portion(art: Int): Portion? {
        val sorte = art and 0x0F
        val zucker = art and ZUCKER != 0
        // Milch gibt es nur beim Kaffee: beim Macchiato gehoert sie zur Sorte.
        val milch = sorte == KAFFEE && art and MILCH != 0
        return when (sorte) {
            // 30 ml Espresso: 63 mg Koffein, praktisch ohne Energie.
            ESPRESSO -> Portion(sorte, false, zucker, 63.0, 2.0 + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Tasse Filterkaffee, 2 dl.
            KAFFEE -> Portion(sorte, milch, zucker, 95.0, 2.0 + (if (milch) MILCH_KCAL else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
            // Ein Espresso auf 2 dl Milch.
            LATTE_MACCHIATO -> Portion(sorte, false, zucker, 63.0, 130.0 + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Dose, 250 ml: mit Zucker sind es 27 g davon, ohne bleibt fast nichts.
            ENERGY -> if (zucker) Portion(sorte, false, true, 80.0, 112.0, 27.0) else Portion(sorte, false, false, 80.0, 8.0, 0.0)
            else -> null
        }
    }

    /** Ein eigenes Getraenk, wie die Uhr es meldet - unsinnige Werte begrenzt. */
    fun eigen(kcal: Long, mg: Long): Portion =
        Portion(EIGEN, false, false, mg.coerceIn(0, 1000).toDouble(), kcal.coerceIn(0, 2000).toDouble(), 0.0)

    private fun zuckerG(zucker: Boolean) = if (zucker) ZUCKER_G else 0.0
    private fun zuckerKcal(zucker: Boolean) = zuckerG(zucker) * KCAL_JE_G_ZUCKER
}
