package coredevices.pebble.kiesel

import android.content.Context
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.first
import org.koin.mp.KoinPlatform
import kotlin.math.roundToInt

/**
 * Ruhe- und Maximalpuls fuer alle Rechnungen in Boulder: Zonen und Belastung
 * in Health, Zonen und Kalorien eines Trainings. Eine Quelle fuer die beiden
 * Grenzen - die Zonen darauf sind bewusst zwei: Health teilt die Pulsreserve
 * wie Google Health (Light bis Peak), ein Training den Maximalpuls wie die
 * Uhr (Zone 1 bis 5).
 */
internal object Herzgrenzen {
    /** Ein eingestellter Maximalpuls ueber Tanaka + so viel ist eher ein Tippfehler als ein Herz. */
    const val TANAKA_SPIELRAUM = 25

    /**
     * Der Median der letzten Naechte (aelteste zuerst) statt der letzten allein:
     * eine Nacht nach Alkohol oder mit Fieber verschiebt sonst alle Grenzen.
     */
    fun ruhepuls(naechte: List<Int>): Int? = Trainingsanalyse.ruhepulsAusNaechten(naechte)

    fun tanaka(alter: Int?): Int? = alter?.takeIf { it in 10..100 }?.let { (208 - 0.7 * it).roundToInt() }

    /** Der Maximalpuls, mit dem gerechnet wird, und ob er aus dem Alter kommt. */
    data class Maximum(val wert: Int, val nachAlter: Boolean)

    /**
     * Der eingestellte Maximalpuls, wenn es einen gibt und er plausibel ist;
     * sonst Tanaka nach dem Alter; ohne Alter die Vorgabe der Uhr. [eingestellt]
     * null heisst "nicht gesetzt" - frueher stand dafuer die Zahl 190, und wer
     * wirklich 190 gemessen hatte, konnte sie nicht einstellen.
     */
    fun maximum(eingestellt: Int?, alter: Int?, ruhe: Int?): Maximum {
        val tanaka = tanaka(alter)
        val gemessen = eingestellt
            ?.takeIf { it > (ruhe ?: Trainingsanalyse.RUHEPULS_ERSATZ) + 20 }
            ?.takeIf { tanaka == null || it <= tanaka + TANAKA_SPIELRAUM }
        return when {
            gemessen != null -> Maximum(gemessen, nachAlter = false)
            tanaka != null -> Maximum(tanaka, nachAlter = true)
            else -> Maximum(eingestellt ?: KieselEinstellungen.MAXPULS_VORGABE, nachAlter = false)
        }
    }

    fun maxpuls(eingestellt: Int?, alter: Int?, ruhe: Int?): Int = maximum(eingestellt, alter, ruhe).wert

    /**
     * Der eingestellte Maximalpuls unter dem, was die Uhr meldet, oder null.
     * [wahl]: was am Telefon zuletzt gewaehlt wurde (false = nach Alter), zu
     * dem Wert [wahlWert], der dabei zur Uhr ging. Meldet die Uhr inzwischen
     * etwas anderes (Konfigseite der Uhr-App), gilt die Wahl nicht mehr. Ohne
     * Wahl heisst die Vorgabe 190 "nicht gesetzt" - so galt es bis 0.42.
     *
     * [wartend]: eine Wahl, die geschickt, aber nicht rechtzeitig bestaetigt
     * wurde. Meldet die Uhr ihren Wert spaeter doch, gilt sie - sonst hiesse
     * eine Schaetzung nach Alter, die die Uhr verspaetet uebernahm, wegen
     * "nicht 190" gemessen, und eine gemessene 190 wieder "nicht gesetzt".
     */
    fun eingestellt(gemeldet: Int, wahl: Boolean?, wahlWert: Int?, wartend: Boolean? = null, wartendWert: Int? = null): Int? {
        val gesetzt = when {
            wartend != null && wartendWert == gemeldet -> wartend
            wahl != null && wahlWert == gemeldet -> wahl
            else -> gemeldet != KieselEinstellungen.MAXPULS_VORGABE
        }
        return gemeldet.takeIf { gesetzt }
    }

    /** Das Alter aus Gravels Einstellungen (Health). */
    suspend fun alter(): Int? = versuche("Herzgrenzen", "Alter lesen") {
        KoinPlatform.getKoin().getOrNull<LibPebble>()?.healthSettings?.first()?.ageYears
    }

    /** Wie [maximum], mit Einstellung und Alter aus Boulder und der Uhr. */
    suspend fun maximum(context: Context, ruhe: Int?): Maximum {
        val k = KieselEinstellungen.stand(context)
        return maximum(k.maxpuls.takeIf { k.maxpulsGesetzt }, alter(), ruhe)
    }

    /** Wie [maxpuls], mit Einstellung und Alter aus Boulder und der Uhr. */
    suspend fun maxpuls(context: Context, ruhe: Int?): Int = maximum(context, ruhe).wert
}
