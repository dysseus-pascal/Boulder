package coredevices.pebble.kiesel.ansicht

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import coredevices.pebble.R
import coredevices.pebble.kiesel.Bandurteil
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.tagespulsUrteil
import java.time.LocalDate
import kotlin.math.abs

/*
 * Die Texte zum Herzen, die mehrere Ansichten teilen: Herzzeile, Nachtblock
 * und Herzseite. Eigene Datei, damit keine Ansicht sie der anderen leiht -
 * wer den Tagesschnitt der Herzseite sucht, findet ihn hier.
 */

/** Der wache Tagesschnitt und was dazu zu sagen ist: Vergleich und Belastung. */
internal class Tagespuls(val wachpuls: Int, val teile: List<String>)

/** Ein Teil eines Herzsatzes: welcher Text, und die Zahl darin - IMMER EIN BETRAG, der Text sagt "above" oder "below". */
internal data class Satzteil(val text: Int, val zahl: Int? = null)

@Composable
private fun Satzteil.wort(): String = if (zahl == null) stringResource(text) else stringResource(text, zahl)

/** Der Teil zum wachen Tagespuls; [abstand] wie in Gesundheitsrechnung.Tagesurteil (mit Vorzeichen), null ohne Lerntage. */
internal fun tagespulsTeil(abstand: Int?): Satzteil = when (abstand?.let { Gesundheitsrechnung.tagespulsUrteil(it) }) {
    null -> Satzteil(R.string.ks_d_tagespuls)
    Vergleich.HOEHER -> Satzteil(R.string.ks_d_tagespuls_hoch, abs(abstand))
    Vergleich.TIEFER -> Satzteil(R.string.ks_d_tagespuls_tief, abs(abstand))
    Vergleich.WIE_IMMER -> Satzteil(R.string.ks_d_tagespuls_gleich)
}

internal fun ruheTeil(ruhe: Herzurteil): Satzteil? = when (ruhe.urteil) {
    Vergleich.WIE_IMMER -> Satzteil(R.string.ks_h_ruhe_wie_immer)
    Vergleich.HOEHER -> Satzteil(R.string.ks_h_ruhe_hoeher, abs(ruhe.abstand))
    Vergleich.TIEFER -> Satzteil(R.string.ks_h_ruhe_tiefer, abs(ruhe.abstand))
    null -> null
}

internal fun hrvTeil(hrv: Bandurteil): Satzteil = when (hrv.urteil) {
    Vergleich.WIE_IMMER -> Satzteil(R.string.ks_h_hrv_wie_immer)
    Vergleich.HOEHER -> Satzteil(R.string.ks_h_hrv_hoeher, abs(hrv.abstand))
    Vergleich.TIEFER -> Satzteil(R.string.ks_h_hrv_tiefer, abs(hrv.abstand))
}

internal fun urteilTeile(ruhe: Herzurteil, hrv: Bandurteil?): List<Satzteil> =
    if (ruhe.urteil == Vergleich.WIE_IMMER && hrv?.urteil == Vergleich.WIE_IMMER) listOf(Satzteil(R.string.ks_h_beide_wie_immer))
    else listOfNotNull(ruheTeil(ruhe), hrv?.let(::hrvTeil))

/**
 * Der Tagespuls fuer die Herzzeile und oben auf der Herzseite - eine Rechnung
 * fuer beide, damit die angetippte Zahl am Ziel wieder steht. Null ohne
 * wachen Tagesschnitt.
 */
@Composable
internal fun tagespuls(s: GesundheitsStand): Tagespuls? {
    val urteil = Gesundheitsrechnung.tagesurteil(s.tagesherz, LocalDate.now()) ?: return null
    val wach = urteil.heute.wachpuls ?: return null
    // Der Tagesschnitt statt des letzten Werts: der sagt, wie der Tag war
    // Ohne Lerntage kein Abstand, dann nur der Schnitt; die Schwelle steht getestet in tagespulsUrteil.
    val vergleich = tagespulsTeil(urteil.abstand).wort()
    val last = urteil.heute.belastung.takeIf { it > 0 }?.let { stringResource(R.string.ks_d_belastung, it) }
    return Tagespuls(wach, listOfNotNull(vergleich, last))
}

/** Der Satz zu Ruhepuls und HRV, wenn wenigstens eins davon ein Urteil hat. */
@Composable
internal fun urteilSatz(ruhe: Herzurteil, hrv: Bandurteil?): String = urteilTeile(ruhe, hrv).map { it.wort() }.joinToString(" ")
