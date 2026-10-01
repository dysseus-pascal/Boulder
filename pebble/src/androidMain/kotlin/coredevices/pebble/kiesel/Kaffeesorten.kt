package coredevices.pebble.kiesel

import android.content.Context
import coredevices.pebble.R

/**
 * Was ein Kaffee aus Drinktervall in die Akte bringt: Koffein, kcal, Zucker.
 *
 * DIE NUMMERN KOMMEN VON DER UHR (CoffeeKind in Drinktervalls src/c/coffee.h)
 * und sind fest: 0 Espresso, 1 Kaffee, 2 Tee (bis Drinktervall 1.16 Latte
 * macchiato), 3 Energy-Drink.
 * Milch und Zucker stehen als Bits ueber der Sorte (COFFEE_KIND).
 *
 * Die Werte sind Durchschnitte fuer eine gewoehnliche Portion - genauer kann
 * eine Uhr es nicht wissen, und fuer die Tageskurve des Koffeins reicht es.
 */
internal object Kaffeesorten {
    const val ESPRESSO = 0
    const val KAFFEE = 1
    const val TEE = 2
    const val ENERGY = 3
    /** Ein eigenes Getraenk aus Drinktervalls Einstellungen: Werte kommen von der Uhr. */
    const val EIGEN = 4

    private const val MILCH = 0x10
    private const val ZUCKER = 0x20

    /** Ein Beutel Zucker, 4 g. */
    private const val ZUCKER_G = 4.0
    private const val KCAL_JE_G_ZUCKER = 4.0
    /** Ein Schuss Vollmilch in Kaffee oder Tee, 30 ml - in den Espresso die Haelfte. */
    private const val MILCH_KCAL = 19.0
    private const val MILCH_KCAL_ESPRESSO = 10.0

    data class Portion(val sorte: Int, val milch: Boolean, val zucker: Boolean, val koffeinMg: Double, val kcal: Double, val zuckerG: Double)

    /** Die Portion zu einem COFFEE_KIND der Uhr - null fuer eine Sorte, die es nicht gibt. */
    fun portion(art: Int): Portion? {
        val sorte = art and 0x0F
        val zucker = art and ZUCKER != 0
        // Milch zu allem ausser dem Energy-Drink (seit Drinktervall 1.17).
        val milch = sorte != ENERGY && art and MILCH != 0
        return when (sorte) {
            // 30 ml Espresso: 63 mg Koffein, praktisch ohne Energie.
            ESPRESSO -> Portion(sorte, milch, zucker, 63.0, 2.0 + (if (milch) MILCH_KCAL_ESPRESSO else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Tasse Filterkaffee, 2 dl.
            KAFFEE -> Portion(sorte, milch, zucker, 95.0, 2.0 + (if (milch) MILCH_KCAL else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Tasse Schwarztee, 2 dl: etwa 40 mg Koffein.
            TEE -> Portion(sorte, milch, zucker, 40.0, 2.0 + (if (milch) MILCH_KCAL else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
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

    private fun sortenId(sorte: Int): Int = when (sorte) {
        ESPRESSO -> R.string.ks_h_espresso
        TEE -> R.string.ks_h_tee
        ENERGY -> R.string.ks_h_energy_drink
        else -> R.string.ks_h_kaffee
    }

    /** Der Name in der Akte: "Coffee, milk, sugar" - wie Drinktervall ihn schreibt. */
    fun name(context: Context, p: Portion, eigenerName: String? = null): String {
        var name = eigenerName ?: context.getString(sortenId(p.sorte))
        if (p.milch) name = context.getString(R.string.ks_h_mit_milch, name)
        if (p.zucker) name = context.getString(R.string.ks_h_mit_zucker, name)
        return name
    }

    /**
     * Sorte, Milch und Zucker aus einem Namen der Akte zurueck - fuer das
     * Bearbeiten. Null, wenn der Name keine unserer Sorten ist.
     */
    fun erkenne(context: Context, name: String): Triple<Int, Boolean, Boolean>? {
        val milch = context.getString(R.string.ks_h_mit_milch, "")
        val zucker = context.getString(R.string.ks_h_mit_zucker, "")
        var rest = name
        val mitZucker = rest.endsWith(zucker).also { if (it) rest = rest.removeSuffix(zucker) }
        val mitMilch = rest.endsWith(milch).also { if (it) rest = rest.removeSuffix(milch) }
        val sorte = listOf(ESPRESSO, KAFFEE, TEE, ENERGY).firstOrNull { context.getString(sortenId(it)) == rest }
            // Die Handeingabe schreibt "Energy" statt "Energy drink".
            ?: if (rest == context.getString(R.string.ks_h_energy)) ENERGY else null
        return sorte?.let { Triple(it, mitMilch, mitZucker) }
    }

    /** Das COFFEE_KIND der Uhr zu Sorte, Milch und Zucker. */
    fun art(sorte: Int, milch: Boolean, zucker: Boolean): Int = sorte or (if (milch) MILCH else 0) or (if (zucker) ZUCKER else 0)
}
