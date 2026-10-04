package coredevices.pebble.kiesel

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DIE FACHZAHLEN, FESTGENAGELT. Die Gleichheitstests (GesundheitsdatenTest,
 * GesundheitsrechnungGleichTest) vergleichen neue mit alter Rechnung - beide
 * mit derselben Konstante. Stand dort TAGESHERZ_TAGE = 28 statt 14, blieben
 * sie gruen. Hier steht jede Zahl, die auf dem Schirm, in einer Notiz oder in
 * einem Urteil wiederkehrt, mit ihrem Wert: eine Aenderung muss hier bewusst
 * nachgezogen werden.
 */
class FachkonstantenTest {

    @Test
    fun zeitraeumeDerSeiten() {
        assertEquals(7, Fenster.WOCHE_TAGE, "Woche der Gesundheitsseite")
        assertEquals(7, Fenster.VERGLEICHSNAECHTE, "Naechte im Schnitt von Your usual")
        assertEquals(3, Fenster.LERNNAECHTE, "ab so vielen Naechten ein Ruhepuls-Urteil")
        assertEquals(30, Fenster.HRV_NAECHTE, "Naechte des HRV-Bands")
        assertEquals(7, Fenster.HRV_LERNNAECHTE, "ab so vielen Naechten ein HRV-Band")
        assertEquals(14L, Fenster.TAGESHERZ_TAGE, "Tage im Vergleich des Tagespulses")
        assertEquals(5, Fenster.TAGESHERZ_LERNTAGE, "ab so vielen Tagen ein Tagespuls-Urteil")
        assertEquals(30L, Fenster.BLUTDRUCK_TAGE)
        assertEquals(7L, Fenster.BLUTDRUCK_ZEILE_TAGE)
        assertEquals(14L, Fenster.AKKU_TAGE)
        assertEquals(7, Fenster.HERZGRENZEN_NAECHTE, "Ruhepuls der Herzgrenzen: Median einer Woche")
        assertEquals(14L, Fenster.HERZGRENZEN_TAGE)
        assertEquals(90L, Fenster.TREND_TAGE)
        assertEquals(28L, Fenster.RICHTUNG_TAGE, "4 weeks vs 4 before")
        assertEquals(7, Fenster.RICHTUNG_MINDESTTAGE)
        assertEquals(8, Fenster.TREND_WOCHEN)
        assertEquals(14, Fenster.LETZTE_TAGE, "Last 14 days")
        assertEquals(30L, Fenster.VERGLEICH_TAGE, "What makes a difference")
        assertEquals(90L, Fenster.KOERPER_TAGE)
        assertEquals(30L, Fenster.KOERPER_VERAENDERUNG_TAGE, "in 30 days")
        assertEquals(90L, Fenster.SPORT_TAGE, "ein Vierteljahr Trainings")
    }

    @Test
    fun ziele() {
        assertEquals(10_000L, Gesundheitsziele.SCHRITTE)
        assertEquals(480, Gesundheitsziele.SCHLAF_MIN)
        assertEquals(30, Gesundheitsziele.AKTIV_MIN)
        assertEquals(10, Gesundheitsziele.DURCHGESCHLAFEN_MIN)
        assertEquals(8, Gesundheitsziele.WASSER_GLAESER)
        assertEquals(300, Gesundheitsziele.GLAS_ML)
    }

    @Test
    fun urteileUndGrenzen() {
        assertEquals(2, RUHEPULS_SPANNE, "so weit neben dem Schnitt heisst as usual")
        assertEquals(3, TAGESPULS_AB)
        assertEquals(15 * 60L, PULS_FRISCH_S, "now")
        assertEquals(60 * 60L, SYNC_ALT_S)
        assertEquals(11, PRAEPARATE_OHNE_PLAN_AB)
        assertEquals(24, Gesundheitsrechnung.TAGESHERZ_MINDESTENS)
        assertEquals(12, Gesundheitsrechnung.NACHT_SPAETESTENS_H)
        assertEquals(300.0, Gesundheitsrechnung.KOFFEIN_HALBWERT_MIN)
        assertEquals(15 * 60L, BLUTDRUCK_SITZUNG_S)
        assertEquals(25, Herzgrenzen.TANAKA_SPIELRAUM)
        assertEquals(2.0, Auswertung.KLAR_AB_FEHLERN, "Standardfehler bis ein Unterschied klar ist")
        assertEquals(1.25, Auswertung.MEDIAN_STREUT)
        assertEquals(5, Auswertung.VERGLEICH_MINDESTENS)
        assertEquals(3, Auswertung.SEITE_MINDESTENS)
        assertEquals(Gesundheitsziele.SCHLAF_MIN - 60, Vergleichsfragen.SEHR_KURZ_MIN)
    }

    @Test
    fun trainingUndKalorien() {
        assertEquals(60, Trainingsanalyse.RUHEPULS_ERSATZ, "Ruhepuls ohne Naechte")
        assertEquals(3.5, Trainingsanalyse.VO2_RUHE)
        assertEquals(5.0, Trainingsanalyse.KCAL_JE_LITER)
        assertEquals(25, Trainingsanalyse.EINBRUCH_BPM)
        assertEquals(45L, Trainingsanalyse.EINBRUCH_S)
        assertEquals(15, Trainingsanalyse.RUECKKEHR_BPM)
        assertEquals(1L, Trainingswerte.FASSUNG_UHR)
        assertEquals(14L, Trainingswerte.NACHHOLEN_TAGE, "Puls-Kalorien so lange nachholen")
        assertEquals(10L, Trainingswerte.TEMPO_TAKT_S)
        assertEquals(60L, Kieselsport.PUFFER_S, "GPS-Nachlauf nach der letzten Pause")
        assertEquals(20 * 60L, Kieselsport.PUFFER_OHNE_PAUSE_S)
        assertEquals(90L, Trainingspausen.TOLERANZ_S)
        assertEquals(30L, Trainingspausen.AUFHEBEN_TAGE)
        assertEquals(2 * 86_400L, Trainingspausen.WARTEN_S, "so lange wartet eine Kurve auf die Zusammenfassung")
    }

    @Test
    fun fristenDerAblageUndDesWidgets() {
        assertEquals(30L, Mithoerer.VERFALL_TAGE, "so lange wartet ein Glas oder Praeparat auf die Akte")
        assertEquals(30L, SupCycle.NACHTRAG_TAGE)
        assertEquals(Duration.ofHours(3), TrainingsDaten.WIDGET_FRIST)
        assertEquals(Duration.ofMinutes(30), Widgetlage.TAKT)
        assertEquals(Duration.ofHours(2), Widgetlage.TRAINING_FRIST)
        assertEquals(Duration.ofHours(3), Widgetlage.MORGEN_FRIST)
        assertEquals(20, Widgetlage.ABEND_AB)
        assertEquals(16, Widgetlage.ZIEL_AB)
        assertEquals(9, Widgetlage.WASSER_AB)
        assertEquals(22, Widgetlage.WASSER_BIS)
    }
}
