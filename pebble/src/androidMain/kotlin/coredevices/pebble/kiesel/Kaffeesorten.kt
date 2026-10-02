package coredevices.pebble.kiesel

import android.content.Context
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import coredevices.pebble.R
import java.time.Instant
import java.time.ZoneOffset

/**
 * Was ein Kaffee aus Drinktervall in die Akte bringt: Koffein, kcal, Zucker.
 *
 * DIE NUMMERN KOMMEN VON DER UHR (CoffeeKind in Drinktervalls src/c/coffee.h)
 * und sind fest: 0 Espresso, 1 Kaffee, 2 Tee (bis Drinktervall 1.16 Latte
 * macchiato), 3 Energy-Drink.
 * Milch, Zucker und koffeinfrei stehen als Bits ueber der Sorte (COFFEE_KIND).
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
    /** Seit Drinktervall 1.18: eingetragen wie die Sorte, aber ohne Koffein. */
    private const val KOFFEINFREI = 0x40

    /** Ein Beutel Zucker, 4 g. */
    private const val ZUCKER_G = 4.0
    private const val KCAL_JE_G_ZUCKER = 4.0
    /** Ein Schuss Vollmilch in Kaffee oder Tee, 30 ml - in den Espresso die Haelfte. */
    private const val MILCH_KCAL = 19.0
    private const val MILCH_KCAL_ESPRESSO = 10.0

    data class Portion(
        val sorte: Int, val milch: Boolean, val zucker: Boolean, val koffeinMg: Double, val kcal: Double, val zuckerG: Double,
        val koffeinfrei: Boolean = false,
    )

    /** Was am Getraenk gewaehlt ist - fuer das Bearbeiten in der Akte. */
    data class Wahl(val sorte: Int, val milch: Boolean, val zucker: Boolean, val koffeinfrei: Boolean)

    /** Die Portion zu einem COFFEE_KIND der Uhr - null fuer eine Sorte, die es nicht gibt. */
    fun portion(art: Int): Portion? {
        val sorte = art and 0x0F
        val zucker = art and ZUCKER != 0
        // Milch zu allem ausser dem Energy-Drink (seit Drinktervall 1.17).
        val milch = sorte != ENERGY && art and MILCH != 0
        // Einen koffeinfreien Energy-Drink gibt es nicht - das Bit zaehlt dort nicht.
        val koffeinfrei = sorte != ENERGY && art and KOFFEINFREI != 0
        val p = when (sorte) {
            // 30 ml Espresso: 63 mg Koffein, praktisch ohne Energie.
            ESPRESSO -> Portion(sorte, milch, zucker, 63.0, 2.0 + (if (milch) MILCH_KCAL_ESPRESSO else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Tasse Filterkaffee, 2 dl.
            KAFFEE -> Portion(sorte, milch, zucker, 95.0, 2.0 + (if (milch) MILCH_KCAL else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Tasse Schwarztee, 2 dl: etwa 40 mg Koffein.
            TEE -> Portion(sorte, milch, zucker, 40.0, 2.0 + (if (milch) MILCH_KCAL else 0.0) + zuckerKcal(zucker), zuckerG(zucker))
            // Eine Dose, 250 ml: mit Zucker sind es 27 g davon, ohne bleibt fast nichts.
            ENERGY -> if (zucker) Portion(sorte, false, true, 80.0, 112.0, 27.0) else Portion(sorte, false, false, 80.0, 8.0, 0.0)
            else -> null
        } ?: return null
        // Die Restmenge eines entkoffeinierten Kaffees (2 bis 5 mg) zaehlt
        // bewusst nicht: wer ihn waehlt, will ihn aus der Koffeinkurve haben.
        return if (koffeinfrei) p.copy(koffeinMg = 0.0, koffeinfrei = true) else p
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

    /** Der Name in der Akte: "Coffee, decaf, milk, sugar" - wie Drinktervall ihn schreibt. */
    fun name(context: Context, p: Portion, eigenerName: String? = null): String {
        var name = eigenerName ?: context.getString(sortenId(p.sorte))
        if (p.koffeinfrei) name = context.getString(R.string.ks_h_koffeinfrei, name)
        if (p.milch) name = context.getString(R.string.ks_h_mit_milch, name)
        if (p.zucker) name = context.getString(R.string.ks_h_mit_zucker, name)
        return name
    }

    /**
     * Sorte, Milch, Zucker und koffeinfrei aus einem Namen der Akte zurueck -
     * fuer das Bearbeiten. Null, wenn der Name keine unserer Sorten ist.
     */
    fun erkenne(context: Context, name: String): Wahl? {
        val milch = context.getString(R.string.ks_h_mit_milch, "")
        val zucker = context.getString(R.string.ks_h_mit_zucker, "")
        val ohne = context.getString(R.string.ks_h_koffeinfrei, "")
        var rest = name
        // Rueckwaerts in der Reihenfolge, in der name() anhaengt.
        val mitZucker = rest.endsWith(zucker).also { if (it) rest = rest.removeSuffix(zucker) }
        val mitMilch = rest.endsWith(milch).also { if (it) rest = rest.removeSuffix(milch) }
        val koffeinfrei = rest.endsWith(ohne).also { if (it) rest = rest.removeSuffix(ohne) }
        val sorte = listOf(ESPRESSO, KAFFEE, TEE, ENERGY).firstOrNull { context.getString(sortenId(it)) == rest }
            // Die Handeingabe schreibt "Energy" statt "Energy drink".
            ?: if (rest == context.getString(R.string.ks_h_energy)) ENERGY else null
        return sorte?.let { Wahl(it, mitMilch, mitZucker, koffeinfrei) }
    }

    /** Das COFFEE_KIND der Uhr zu Sorte, Milch, Zucker und koffeinfrei. */
    fun art(sorte: Int, milch: Boolean, zucker: Boolean, koffeinfrei: Boolean = false): Int =
        sorte or (if (milch) MILCH else 0) or (if (zucker) ZUCKER else 0) or (if (koffeinfrei) KOFFEINFREI else 0)

    /** Die Sorten, die sich in der App waehlen lassen - in der Reihenfolge der Uhr. */
    val WAEHLBAR = listOf(ESPRESSO, KAFFEE, TEE, ENERGY)

    /**
     * Der Eintrag fuer die Akte. EIN WEG FUER UHR, HANDEINGABE UND BEARBEITEN:
     * sonst hatte dieselbe Tasse je nach Herkunft andere Werte, und ein
     * Speichern ohne Aenderung schrieb neue.
     */
    fun satz(
        p: Portion, name: String, metadata: Metadata, beginn: Instant, ende: Instant = beginn.plusSeconds(60),
        beginnVersatz: ZoneOffset = versatz(beginn), endeVersatz: ZoneOffset = versatz(ende),
    ): NutritionRecord = NutritionRecord(
        startTime = beginn, startZoneOffset = beginnVersatz,
        endTime = ende, endZoneOffset = endeVersatz,
        name = name,
        // Ohne Koffein kein Koffeinfeld - sonst zaehlte ein Smoothie als 0-mg-Dosis.
        caffeine = if (p.koffeinMg > 0) Mass.milligrams(p.koffeinMg) else null,
        energy = Energy.kilocalories(p.kcal),
        sugar = if (p.zuckerG > 0) Mass.grams(p.zuckerG) else null,
        metadata = metadata,
    )
}
