package coredevices.pebble.kiesel

import android.content.Context
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.first
import org.koin.mp.KoinPlatform
import kotlin.math.roundToInt

/**
 * Ruhe- und Maximalpuls fuer alle Rechnungen in Boulder: Zonen und Belastung
 * in Health, Zonen und Kalorien eines Trainings. Eine Quelle, damit "Zone" auf
 * Herz- und Sportseite dasselbe heisst.
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

    /**
     * Der eingestellte Maximalpuls, wenn er gemessen ist (nicht die Vorgabe) und
     * plausibel; sonst Tanaka nach dem Alter; ohne Alter die Vorgabe.
     */
    fun maxpuls(eingestellt: Int, alter: Int?, ruhe: Int?): Int {
        val tanaka = tanaka(alter)
        val gemessen = eingestellt.takeIf { it != KieselEinstellungen.MAXPULS_VORGABE }
            ?.takeIf { it > (ruhe ?: Trainingsanalyse.RUHEPULS_ERSATZ) + 20 }
            ?.takeIf { tanaka == null || it <= tanaka + TANAKA_SPIELRAUM }
        return gemessen ?: tanaka ?: eingestellt
    }

    /** Wie [maxpuls], mit Einstellung und Alter aus Boulder und der Uhr. */
    suspend fun maxpuls(context: Context, ruhe: Int?): Int {
        val alter = runCatching {
            KoinPlatform.getKoin().getOrNull<LibPebble>()?.healthSettings?.first()?.ageYears
        }.getOrNull()
        return maxpuls(KieselEinstellungen.stand(context).maxpuls, alter, ruhe)
    }
}
