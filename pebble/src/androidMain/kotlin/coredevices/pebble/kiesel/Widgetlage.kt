package coredevices.pebble.kiesel

import coredevices.pebble.R
import kotlin.math.roundToInt
import java.time.Duration
import java.time.LocalDateTime

/**
 * Welche Lage gerade gilt - und was das Widget deshalb gross zeigt.
 *
 * DAS WIDGET WEISS, WAS GERADE ZAEHLT. Morgens nach dem Aufwachen gibt es noch
 * keine Schritte, aber die Nacht ist die Auskunft, die man will. Nach einem
 * Training will man das Training sehen. Am Abend die Bilanz. Statt vier
 * festen Kacheln gibt es eine Buehne, die wechselt, und drei kleine Kacheln
 * fuer den Rest.
 *
 * EINE VORRANGLISTE, KEIN GEWICHT: Training vor Morgen vor Erinnerung vor
 * Abend vor Tag. Wer im Morgen trainiert, sieht danach das Training.
 *
 * REINE LOGIK OHNE SCHIRM, damit sie sich pruefen laesst: hinein geht ein
 * Blick auf den Stand, heraus kommt, was zu zeigen ist. Die Woerter kommen
 * ueber [Texte] - in der App aus den Ressourcen, im Test aus derselben Datei.
 */
internal object Widgetlage {

    enum class Art { TRAINING, MORGEN, ERINNERUNG, ABEND, TAG }

    /** Was das Widget vom Stand braucht - gesammelt, damit die Logik ohne Akte prueft. */
    data class Blick(
        val jetzt: LocalDateTime,
        /** Wann die letzte Nacht endete, null wenn keine eingetragen ist. */
        val schlafEnde: LocalDateTime?,
        val schlafMin: Double?,
        val schlafZielMin: Double?,
        val schlafWocheMin: Double?,
        val erholsamAnteil: Double?,     //< 0..1, null wenn unbekannt
        val ruhepuls: Double?,
        val hrv: Double?,
        /** Das letzte Training, null wenn keines in Reichweite. */
        val trainingEnde: LocalDateTime?,
        val trainingName: String?,
        val trainingMin: Long?,
        val trainingPuls: Double?,
        val trainingKm: Double?,
        val trainingBeginn: Long?,       //< Epoch-Sekunden, fuer den Sprung zur Seite
        val schritte: Double?,
        val schritteZiel: Double?,
        val aktivMin: Double?,
        val aktivZiel: Double?,
        val wasserMl: Double?,
        val wasserZiel: Double?,
        val glasMl: Double,
        /** Die Praeparate von heute, wie die Gesundheitsseite sie zeigt - mit Planzeit. */
        val praeparate: List<SupCycle.Eintrag>,
        val koffeinMg: Double?,
        /** Von Hand gewaehlt, gilt bis dann. */
        val wunsch: Art?,
        /** Gravels "Imperial Units" - die Strecke in mi. Die Rechnung bleibt ohne Android, der Schalter kommt von aussen. */
        val imperial: Boolean = false,
    )

    /** Eine kleine Kachel: Beschriftung, Wert, Anteil am Ziel. */
    data class Kachel(val schluessel: String, val name: String, val text: String?, val anteil: Float)

    data class Lage(
        val art: Art,
        val wort: String,          //< das Wort oben: "Nacht", "Training", "Heute"
        val name: String,          //< Beschriftung der Buehne
        val gross: String,         //< die grosse Zahl
        val einheit: String,
        val satz: String,          //< der Satz darunter
        val anteil: Float?,        //< Balken unter der grossen Zahl, oder keiner
        val kacheln: List<Kachel>, //< die drei kleinen
        val trainingBeginn: Long?, //< gesetzt, wenn ein Tipp auf die Trainingsseite fuehren soll
    )

    private val TRAINING_FRIST: Duration = Duration.ofHours(2)
    private val MORGEN_FRIST: Duration = Duration.ofHours(3)
    private const val ABEND_AB = 20
    /** Ohne Planzeit mahnt das Widget erst ab dieser Stunde - morgens ist nichts versaeumt. */
    private const val PRAEPARATE_OHNE_PLAN_AB = 11

    fun ermittle(b: Blick, t: Texte): Lage {
        val art = b.wunsch ?: when {
            b.trainingEnde != null && Duration.between(b.trainingEnde, b.jetzt) < TRAINING_FRIST &&
                !b.trainingEnde.isAfter(b.jetzt) -> Art.TRAINING
            b.schlafEnde != null && b.schlafMin != null && Duration.between(b.schlafEnde, b.jetzt) < MORGEN_FRIST &&
                !b.schlafEnde.isAfter(b.jetzt) -> Art.MORGEN
            erinnerung(b, t) != null -> Art.ERINNERUNG
            b.jetzt.hour >= ABEND_AB || zielErreicht(b) -> Art.ABEND
            else -> Art.TAG
        }
        return when (art) {
            Art.TRAINING -> training(b, t)
            Art.MORGEN -> morgen(b, t)
            Art.ERINNERUNG -> erinnerungLage(b, t)
            Art.ABEND -> abend(b, t)
            Art.TAG -> tag(b, t)
        }
    }

    private fun zielErreicht(b: Blick): Boolean {
        val s = b.schritte ?: return false
        val z = b.schritteZiel ?: return false
        return s >= z && b.jetzt.hour >= 16
    }

    // --- Die Kacheln, aus denen die Buehne waehlt ---

    private fun kSchritte(b: Blick, t: Texte) = Kachel("schritte", t.text(R.string.ks_h_schritte), ganz(b.schritte), anteil(b.schritte, b.schritteZiel))
    private fun kAktiv(b: Blick, t: Texte) = Kachel("aktiv", t.text(R.string.ks_h_aktiv), ganz(b.aktivMin)?.let { Formate.Angabe(it, "min").text }, anteil(b.aktivMin, b.aktivZiel))
    private fun kSchlaf(b: Blick, t: Texte) = Kachel("schlaf", t.text(R.string.ks_h_schlaf_n), dauer(b.schlafMin)?.text, anteil(b.schlafMin, b.schlafZielMin))
    /** Ohne Wasser (kein Drinktervall auf der Uhr) keine Kachel - nicht "0 l". In Litern wie die App. */
    private fun kWasser(b: Blick, t: Texte) = b.wasserMl?.let { Kachel("wasser", t.text(R.string.ks_h_wasser_n), Formate.Angabe(liter(it), "l").text, anteil(it, b.wasserZiel)) }

    // Die Werte des Widgets sind Doubles aus der Akte; gerundet wird hier, geformt in Formate.
    private fun ganz(d: Double?): String? = d?.let { Formate.zahl(it.roundToInt().toLong()) }
    private fun dauer(min: Double?): Formate.Angabe? = min?.let { Formate.dauerTeile(it.roundToInt().toLong()) }
    private fun liter(ml: Double): String = Formate.liter(ml.roundToInt())

    private fun anteil(zahl: Double?, ziel: Double?): Float {
        if (zahl == null || ziel == null || ziel <= 0) return 0f
        return (zahl / ziel).coerceIn(0.0, 1.0).toFloat()
    }

    // --- Die Lagen ---

    private fun morgen(b: Blick, t: Texte): Lage {
        val min = b.schlafMin ?: 0.0
        val teile = mutableListOf<String>()
        b.erholsamAnteil?.let { teile += t.text(R.string.ks_wl_erholsam, Formate.prozent(it)) }
        b.ruhepuls?.let { teile += t.text(R.string.ks_w_ruhepuls_wert, ganz(it).orEmpty()) }
        b.hrv?.let { teile += "HRV " + Formate.Angabe(ganz(it).orEmpty(), "ms").text }
        val vergleich = b.schlafWocheMin?.let { woche ->
            when {
                min >= woche + 30 -> t.text(R.string.ks_wl_laenger)
                min <= woche - 30 -> t.text(R.string.ks_wl_kuerzer)
                else -> t.text(R.string.ks_wl_wie_sonst)
            }
        }
        val satz = buildString {
            if (teile.isNotEmpty()) append(teile.joinToString(" · "))
            if (vergleich != null) { if (isNotEmpty()) append(" — "); append(vergleich) }
            if (isEmpty()) append(t.text(R.string.ks_wl_nacht_eingetragen))
        }
        // Zahl und Einheit getrennt wie in der App: "7:42" gross, "h" klein daneben.
        val dauer = Formate.dauerTeile(min.roundToInt().toLong())
        return Lage(
            Art.MORGEN, t.text(R.string.ks_wl_nacht), t.text(R.string.ks_wl_geschlafen),
            dauer.zahl, dauer.einheit.orEmpty(), satz, anteil(b.schlafMin, b.schlafZielMin),
            listOfNotNull(kSchritte(b, t), kAktiv(b, t), kWasser(b, t)), null,
        )
    }

    private fun training(b: Blick, t: Texte): Lage {
        val teile = mutableListOf<String>()
        b.trainingPuls?.let { teile += t.text(R.string.ks_wl_puls_schnitt, ganz(it).orEmpty()) }
        b.trainingKm?.takeIf { it >= 0.1 }?.let { teile += Formate.distanz(it * 1000, b.imperial).text }
        val vor = b.trainingEnde?.let { Duration.between(it, b.jetzt).toMinutes() } ?: 0
        val wann = if (vor < 60) t.text(R.string.ks_wl_beendet_min, vor)
                   else t.text(R.string.ks_wl_beendet_h, vor / 60)
        val satz = (teile + wann).joinToString(" · ")
        val dauer = dauer(b.trainingMin?.toDouble())
        return Lage(
            Art.TRAINING, t.text(R.string.ks_training), b.trainingName ?: t.text(R.string.ks_training),
            dauer?.zahl ?: Formate.FEHLT, dauer?.einheit.orEmpty(), satz, null,
            listOfNotNull(kSchritte(b, t), kWasser(b, t), kSchlaf(b, t)), b.trainingBeginn,
        )
    }

    /**
     * Gibt es etwas, woran zu erinnern ist? Den Satz dazu, sonst null.
     *
     * Vorne steht ein Schluessel ("praeparate", "wasser"), kein Wort: die
     * Lage darunter entscheidet danach, und ein uebersetztes Wort taugt
     * dafuer nicht.
     */
    private fun erinnerung(b: Blick, t: Texte): Pair<String, String>? {
        val versaeumt = versaeumtePraeparate(b)
        if (versaeumt.isNotEmpty()) {
            val namen = versaeumt.take(2).joinToString(", ") + (if (versaeumt.size > 2) " …" else "")
            return "praeparate" to t.text(R.string.ks_wl_noch_offen, namen)
        }
        // Wasser: was bis jetzt haette getrunken sein sollen - zwei Glaeser
        // dahinter ist eine Erinnerung wert. Das Soll folgt [wasserSoll].
        val ziel = b.wasserZiel
        val ml = b.wasserMl
        if (ziel != null && ml != null && b.jetzt.hour in 9..21) {
            val soll = ziel * wasserSoll(b.jetzt.hour + b.jetzt.minute / 60.0)
            val fehltGlaeser = ((soll - ml) / b.glasMl).toInt()
            if (fehltGlaeser >= 2) return "wasser" to t.mehrzahl(R.plurals.ks_wl_glaeser_hinterher, fehltGlaeser, fehltGlaeser)
        }
        return null
    }

    /**
     * Die Praeparate, an die zu erinnern ist: nicht genommen, und ihre
     * Planzeit ist vorbei - das Abendpraeparat ist mittags noch nicht
     * versaeumt. Ohne Planzeit ab [PRAEPARATE_OHNE_PLAN_AB] Uhr.
     */
    fun versaeumtePraeparate(b: Blick): List<String> = b.praeparate.filter { e ->
        !e.genommen && (e.zeit?.let { !b.jetzt.toLocalTime().isBefore(it) } ?: (b.jetzt.hour >= PRAEPARATE_OHNE_PLAN_AB))
    }.map { it.name }

    /**
     * Welcher Anteil des Tagesziels bis zu dieser Stunde getrunken sein soll.
     *
     * NICHT GLEICHMAESSIG. Wer morgens trinkt, holt nach der Nacht auf, und am
     * Abend will niemand noch einen Liter nachschuetten. Deshalb vorne mehr:
     * bis Mittag 40 %, bis fuenf Uhr nachmittags 75 %, bis zehn Uhr abends
     * alles - dazwischen gleichmaessig. Eine gleichmaessige Kurve von acht
     * bis zweiundzwanzig Uhr mahnte am Nachmittag, obwohl der Morgen schon
     * die Arbeit gemacht hatte.
     */
    fun wasserSoll(stunde: Double): Double {
        val punkte = listOf(8.0 to 0.0, 12.0 to 0.40, 17.0 to 0.75, 22.0 to 1.0)
        if (stunde <= punkte.first().first) return 0.0
        if (stunde >= punkte.last().first) return 1.0
        for (i in 1 until punkte.size) {
            val (h1, a1) = punkte[i]
            if (stunde <= h1) {
                val (h0, a0) = punkte[i - 1]
                return a0 + (a1 - a0) * (stunde - h0) / (h1 - h0)
            }
        }
        return 1.0
    }

    private fun erinnerungLage(b: Blick, t: Texte): Lage {
        val (was, satz) = erinnerung(b, t) ?: ("" to "")
        // Ohne Mahnung fuehrte die Wasserkachel hierher - dann das Wasser, nicht "0 open".
        return if (was != "praeparate") {
            Lage(
                Art.ERINNERUNG, t.text(R.string.ks_wl_erinnerung), t.text(R.string.ks_h_wasser_n),
                liter(b.wasserMl ?: 0.0), "l", satz, anteil(b.wasserMl, b.wasserZiel),
                listOfNotNull(kSchritte(b, t), kAktiv(b, t), kSchlaf(b, t)), null,
            )
        } else {
            Lage(
                Art.ERINNERUNG, t.text(R.string.ks_wl_erinnerung), t.text(R.string.ks_w_praeparate),
                Formate.zahl(versaeumtePraeparate(b).size.toLong()), t.text(R.string.ks_wl_offen), satz, null,
                listOfNotNull(kSchritte(b, t), kWasser(b, t), kSchlaf(b, t)), null,
            )
        }
    }

    private fun abend(b: Blick, t: Texte): Lage {
        val teile = mutableListOf<String>()
        val s = b.schritte
        val z = b.schritteZiel
        if (s != null && z != null) {
            teile += if (s >= z) t.text(R.string.ks_wl_schrittziel_erreicht)
                     else t.text(R.string.ks_wl_schritte_zum_ziel, ganz(z - s).orEmpty())
        }
        val ml = b.wasserMl
        val wz = b.wasserZiel
        if (ml != null && wz != null && ml < wz) {
            val fehlt = Math.ceil((wz - ml) / b.glasMl).toInt()
            teile += t.mehrzahl(R.plurals.ks_wl_glaeser_fehlen, fehlt, fehlt)
        }
        b.koffeinMg?.takeIf { it > 0 }?.let { teile += t.text(R.string.ks_wl_koffein_mg, ganz(it).orEmpty()) }
        return Lage(
            Art.ABEND, t.text(R.string.ks_wl_bilanz), t.text(R.string.ks_h_schritte),
            ganz(b.schritte) ?: Formate.FEHLT, "",
            teile.ifEmpty { listOf(t.text(R.string.ks_wl_tag_eingetragen)) }.joinToString(" · "),
            anteil(b.schritte, b.schritteZiel),
            listOfNotNull(kAktiv(b, t), kWasser(b, t), kSchlaf(b, t)), null,
        )
    }

    private fun tag(b: Blick, t: Texte): Lage {
        val s = b.schritte
        val z = b.schritteZiel
        val satz = when {
            s == null -> t.text(R.string.ks_wl_keine_schritte)
            z == null -> t.text(R.string.ks_wl_schritte_bis_jetzt, ganz(s).orEmpty())
            s >= z -> t.text(R.string.ks_wl_ziel_erreicht)
            else -> t.text(R.string.ks_wl_bis_zum_ziel, ganz(z - s).orEmpty())
        }
        return Lage(
            Art.TAG, t.text(R.string.ks_h_heute), t.text(R.string.ks_h_schritte),
            ganz(b.schritte) ?: Formate.FEHLT, "", satz, anteil(b.schritte, b.schritteZiel),
            listOfNotNull(kAktiv(b, t), kWasser(b, t), kSchlaf(b, t)), null,
        )
    }
}

/** Die Texte der Lage - ueber den Context, damit die Rechnung selbst kein Android braucht. */
internal interface Texte {
    fun text(id: Int, vararg a: Any): String
    fun mehrzahl(id: Int, n: Int, vararg a: Any): String
}

/**
 * Die kleinen Zahlenhelfer aus Kiesel-Helper als Weiterleitungen auf
 * [Formate]. Das Widget nimmt Formate inzwischen direkt; nur
 * WidgetlageGetraenkeTest ruft sie noch - beide fallen beim Aufraeumen weg.
 */
@Deprecated("Formate: zahl, dauer, liter, distanz")
internal object Zahlen {
    fun ganz(d: Double?): String? = d?.let { Formate.zahl(it.roundToInt().toLong()) }
    fun eine(d: Double?): String? = d?.let { Formate.zahl(it, 1) }
    fun dauer(min: Double?): String? = min?.roundToInt()?.let { Formate.dauer(it.toLong()) }
    /** Wasser in Litern wie auf der Gesundheitsseite ("1.2"), ohne Einheit. */
    fun liter(ml: Double): String = Formate.liter(ml.roundToInt())
}
