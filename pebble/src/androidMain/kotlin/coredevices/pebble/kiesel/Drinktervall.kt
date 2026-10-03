package coredevices.pebble.kiesel

import android.content.Context
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.units.Volume
import coredevices.pebble.R
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Drinktervall: getrunkenes Wasser in die Akte - wie in Kiesel-Helper
 * (Aufgaben.wasser).
 *
 * DIE UHR BESTIMMT ZIEL UND GLASGROESSE, nicht diese App: beides wird auf der
 * Uhr oder in der Konfigseite von Drinktervall eingestellt und kommt mit jeder
 * Meldung mit. Hier wird es nur gemerkt, fuer die Gesundheitsansicht.
 */
internal object Drinktervall {
    private const val DATEI = "kiesel-wasser"

    val UUID: Uuid = Uuid.parse("5b0f7a3e-2c8d-4b61-9e4f-7d2a1c9b8e50")

    /**
     * Die Feldnummern folgen der Reihenfolge der messageKeys in der
     * package.json der Uhr-App, ab 10000. Wer dort eine Zeile dazwischen
     * schiebt, verschiebt alle folgenden.
     */
    const val DT_GLASSES = 10001   //< das heutige Ziel in Glaesern, bei jeder Meldung
    const val DT_TARGET = 10007    //< das Soll in Glaesern, ohne die Erhoehung des Tages
    const val DT_GLASS_ML = 10008
    const val DT_DRANK_AT = 10009
    // Seit Drinktervall 1.14.0: ein getrunkener Kaffee, Zeitpunkt und Sorte
    // (Sorte in den unteren vier Bits, Milch 0x10, Zucker 0x20, seit 1.18
    // koffeinfrei 0x40).
    const val DT_COFFEE_AT = 10012
    const val DT_COFFEE_KIND = 10013
    // Seit 1.16.0: ein eigenes Getraenk (Sorte 4) bringt Name, kcal und Koffein mit.
    const val DT_DRINK_NAME = 10033
    const val DT_DRINK_KCAL = 10034
    const val DT_DRINK_MG = 10035

    data class Ziel(val glaeser: Int, val glasMl: Int) {
        val ml: Int get() = glaeser * glasMl
    }

    /**
     * Das Ziel fuer [heute]. DAS ERHOEHTE ZIEL GILT EINEN TAG: auf der Uhr
     * laesst es sich fuer heute anheben, am naechsten Morgen gilt wieder das
     * Soll. Bis die Uhr sich am neuen Tag meldet, zaehlt deshalb das Soll -
     * ohne gemerktes Soll (vor Boulder 0.43) das zuletzt gemeldete Ziel.
     */
    fun zielFuer(heute: LocalDate, tag: LocalDate?, glaeser: Int, soll: Int?, glasMl: Int): Ziel =
        Ziel(if (tag == heute || soll == null) glaeser else soll, glasMl)

    /** Das heutige Ziel - null, wenn Drinktervall noch nie eines meldete. */
    fun ziel(context: Context): Ziel? {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (!p.contains("glaeser")) return null
        val tag = p.getString("tag", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val soll = if (p.contains("soll")) p.getInt("soll", Gesundheitsziele.WASSER_GLAESER) else null
        return zielFuer(LocalDate.now(), tag, p.getInt("glaeser", Gesundheitsziele.WASSER_GLAESER), soll, p.getInt("glas_ml", Gesundheitsziele.GLAS_ML))
    }

    /** Das Soll ohne die Erhoehung eines einzelnen Tages - fuer Verlaeufe ueber viele Tage. */
    fun soll(context: Context): Ziel? {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (!p.contains("glaeser")) return null
        val glaeser = if (p.contains("soll")) p.getInt("soll", Gesundheitsziele.WASSER_GLAESER) else p.getInt("glaeser", Gesundheitsziele.WASSER_GLAESER)
        return Ziel(glaeser, p.getInt("glas_ml", Gesundheitsziele.GLAS_ML))
    }

    /** Ob eine Meldung ein Glas oder einen Kaffee traegt - nur solche muessen das Bestaetigen ueberleben. */
    fun traegtEintrag(n: KieselNachricht): Boolean = DT_DRANK_AT in n.zahlen || DT_COFFEE_AT in n.zahlen

    /** Die Felder eines Kaffees - bleibt nur das Glas offen, faellt der Kaffee aus dem Rest. */
    private val KAFFEE_FELDER = setOf(DT_COFFEE_AT, DT_COFFEE_KIND, DT_DRINK_KCAL, DT_DRINK_MG)

    /**
     * Was von [n] noch in die Akte muss: nur der gescheiterte Teil. Ein
     * eingetragener Kaffee bliebe sonst im Rest und kaeme bei jedem Start
     * wieder - auch nachdem er im Strahl geloescht oder geaendert wurde.
     */
    fun rest(n: KieselNachricht, kaffeeFehlt: Boolean, glasFehlt: Boolean): KieselNachricht = n.copy(
        zahlen = n.zahlen.filterKeys { k -> (kaffeeFehlt || k !in KAFFEE_FELDER) && (glasFehlt || k != DT_DRANK_AT) },
        texte = if (kaffeeFehlt) n.texte else n.texte - DT_DRINK_NAME,
    )

    /**
     * [nachgeholt]: die Meldung lag seit einem frueheren Start - Ziel und
     * Soll darin sind die von damals und gelten heute nicht mehr.
     */
    suspend fun verarbeite(context: Context, n: KieselNachricht, nachgeholt: Boolean = false): Verarbeitet? {
        // KAFFEE UND GLAS KOENNEN IN DERSELBEN MELDUNG STEHEN: die Uhr haengt
        // von beiden Schlangen je das aelteste an. Beides wird eingetragen.
        val kaffee = kaffee(context, n)
        val glas = wasser(context, n, merkeZiel = !nachgeholt)
        val teile = listOfNotNull(kaffee, glas)
        if (teile.isEmpty()) return null
        val kaffeeFehlt = Kieselsport.gescheitert(context, kaffee)
        val glasFehlt = Kieselsport.gescheitert(context, glas)
        return Verarbeitet(teile.joinToString(" · "), kaffeeFehlt || glasFehlt, rest(n, kaffeeFehlt, glasFehlt))
    }

    /** Ein Kaffee aus Drinktervall: Koffein, kcal und Zucker in einem Ernaehrungseintrag. */
    private suspend fun kaffee(context: Context, n: KieselNachricht): String? {
        val wann = n.zahlen[DT_COFFEE_AT] ?: return null
        val art = n.zahlen[DT_COFFEE_KIND] ?: return null
        // Ein eigenes Getraenk rechnet nicht Boulder, es kommt fertig von der Uhr.
        val eigen = (art.toInt() and 0x0F) == Kaffeesorten.EIGEN
        val p = if (eigen) {
            Kaffeesorten.eigen(n.zahlen[DT_DRINK_KCAL] ?: 0, n.zahlen[DT_DRINK_MG] ?: 0)
        } else {
            Kaffeesorten.portion(art.toInt())
        } ?: return null
        if (wann <= 0) return null
        // Auch der Kaffee faehrt mit, bis das Telefon bestaetigt - also
        // womoeglich zweimal.
        if (!Riegel.neu(context, "drinktervall-kaffee", wann.toString())) return null

        val beginn = minOf(Instant.ofEpochSecond(wann), Instant.now())
        val name = Kaffeesorten.name(context, p, if (eigen) n.texte[DT_DRINK_NAME]?.ifBlank { null } ?: context.getString(R.string.ks_h_getraenk_eigen) else null)
        val satz = Kaffeesorten.satz(p, name, Kieselsport.vonDerUhr("drinktervall-kaffee-$wann"), beginn)
        return Kieselsport.schreibe(context, satz, context.getString(R.string.ks_w_kaffee_eingetragen, name), riegel = "drinktervall-kaffee")
    }

    /** Ein Glas Wasser aus Drinktervall - und nebenbei, mit [merkeZiel], Ziel und Glasgroesse. */
    private suspend fun wasser(context: Context, n: KieselNachricht, merkeZiel: Boolean): String? {
        val felder = n.zahlen
        // DIE GLASGROESSE NUR OHNE GLAS: traegt die Meldung ein Glas, steht in
        // GLASS_ML dessen Menge - und die kann eine andere sein.
        val mitGlas = felder[DT_DRANK_AT] != null
        if (merkeZiel) context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().apply {
            felder[DT_GLASSES]?.let { putInt("glaeser", it.toInt()); putString("tag", LocalDate.now().toString()) }
            felder[DT_TARGET]?.takeIf { it > 0 }?.let { putInt("soll", it.toInt()) }
            if (!mitGlas) felder[DT_GLASS_ML]?.let { putInt("glas_ml", it.toInt()) }
            apply()
        }

        val ml = felder[DT_GLASS_ML] ?: return null
        val wann = felder[DT_DRANK_AT] ?: return null
        if (ml <= 0 || wann <= 0) return null
        // NICHT ZWEIMAL FUER DENSELBEN AUGENBLICK: die Uhr schickt ihren Stand
        // bei jeder Gelegenheit mit, nicht nur beim Trinken.
        if (!Riegel.neu(context, "drinktervall", wann.toString())) return null

        // HOECHSTENS JETZT: geht die Uhr ein paar Sekunden vor, laege das Glas
        // in der Zukunft - und die Akte lehnt einen solchen Eintrag ab.
        val beginn = minOf(Instant.ofEpochSecond(wann), Instant.now())
        val satz = HydrationRecord(
            startTime = beginn,
            startZoneOffset = versatz(beginn),
            // Eine Minute: die Akte will eine Spanne, und ein Glas trinkt sich
            // nicht in einem Augenblick.
            endTime = beginn.plusSeconds(60),
            endZoneOffset = versatz(beginn),
            volume = Volume.milliliters(ml.toDouble()),
            metadata = Kieselsport.vonDerUhr("drinktervall-$wann"),
        )
        return Kieselsport.schreibe(context, satz, context.getString(R.string.ks_w_eingetragen, ml.toInt()), riegel = "drinktervall")
    }
}
