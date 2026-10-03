package coredevices.pebble.kiesel.ansicht

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import coredevices.pebble.R
import coredevices.pebble.kiesel.Bandurteil
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.hrvUrteil
import java.time.LocalDate

/*
 * Die Texte zum Herzen, die mehrere Ansichten teilen: Herzzeile, Nachtblock
 * und Herzseite. Eigene Datei, damit keine Ansicht sie der anderen leiht -
 * wer den Tagesschnitt der Herzseite sucht, findet ihn hier.
 */

/** Der wache Tagesschnitt und was dazu zu sagen ist: Vergleich und Belastung. */
internal class Tagespuls(val wachpuls: Int, val teile: List<String>)

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
    val vergleich = when {
        urteil.abstand == null -> stringResource(R.string.ks_d_tagespuls)
        urteil.abstand >= 3 -> stringResource(R.string.ks_d_tagespuls_hoch, urteil.abstand)
        urteil.abstand <= -3 -> stringResource(R.string.ks_d_tagespuls_tief, -urteil.abstand)
        else -> stringResource(R.string.ks_d_tagespuls_gleich)
    }
    val last = urteil.heute.belastung.takeIf { it > 0 }?.let { stringResource(R.string.ks_d_belastung, it) }
    return Tagespuls(wach, listOfNotNull(vergleich, last))
}

/**
 * Das Urteil zur HRV fuer den Nachtblock - nur, wenn es eins gibt; erklaert
 * wird nichts. Das zum Ruhepuls steht schon im Satz darueber und hier nicht
 * noch einmal.
 */
@Composable
internal fun herzHinweis(s: GesundheitsStand): String? {
    if (s.nacht == null) return null
    // Die HRV nach ihrem Band, wie die HRV-Karte der Herzseite
    val hrv = Gesundheitsrechnung.hrvUrteil(s.hrvTage.lastOrNull()) ?: return null
    return urteilSatz(Herzurteil(0, null, null, 0), hrv)
}

/** Der Satz zu Ruhepuls und HRV, wenn wenigstens eins davon ein Urteil hat. */
@Composable
internal fun urteilSatz(ruhe: Herzurteil, hrv: Bandurteil?): String {
    if (ruhe.urteil == Vergleich.WIE_IMMER && hrv?.urteil == Vergleich.WIE_IMMER) return stringResource(R.string.ks_h_beide_wie_immer)
    val teile = listOfNotNull(
        when (ruhe.urteil) {
            Vergleich.WIE_IMMER -> stringResource(R.string.ks_h_ruhe_wie_immer)
            Vergleich.HOEHER -> stringResource(R.string.ks_h_ruhe_hoeher, ruhe.abstand)
            Vergleich.TIEFER -> stringResource(R.string.ks_h_ruhe_tiefer, ruhe.abstand)
            null -> null
        },
        hrv?.let {
            when (it.urteil) {
                Vergleich.WIE_IMMER -> stringResource(R.string.ks_h_hrv_wie_immer)
                Vergleich.HOEHER -> stringResource(R.string.ks_h_hrv_hoeher, it.abstand)
                Vergleich.TIEFER -> stringResource(R.string.ks_h_hrv_tiefer, it.abstand)
            }
        },
    )
    return teile.joinToString(" ")
}
