package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Die Worte der Gesundheitsseite - welches Urteil, welcher Satz, welche
 * Zeile. Hier und nicht in der Ansicht, damit ein Test die Schwellen ohne
 * Oberflaeche pruefen kann.
 */

internal enum class Schlafurteil { AUSGERUHT, KURZ, SEHR_KURZ }

/** Die Tageszeit des Grusses oben auf der Seite. */
internal enum class Tageszeit { MORGEN, TAG, ABEND }

internal data class Tagessatzdaten(
    val tageszeit: Tageszeit,
    /** Null ohne Nacht - dann spricht der Satz nur von Schritten. */
    val urteil: Schlafurteil?,
    /** Die Schlafminuten der letzten Nacht, null ohne Nacht. */
    val schlafMinuten: Int?,
    /** Der Ruhepuls der letzten Nacht gegen die frueheren, null ohne Nacht. */
    val ruhe: Herzurteil?,
    /** Auch fuer den Nachtblock im Strahl - eine Schwelle fuer Satz und Strahl. */
    val durchgeschlafen: Boolean,
    val schritte: Long,
    /** Die Belastung des Tages gegen die frueheren Tage, null ohne Urteil. */
    val haerte: Vergleich?,
    /** Das erste versaeumte Praeparat, sonst null. */
    val offen: String?,
    /** Weder Schritte noch Puls noch Nacht: die Uhr hat heute nichts geschickt. */
    val ohneDaten: Boolean,
)

internal enum class Vergleich { WIE_IMMER, HOEHER, TIEFER }

/**
 * Der Ruhepuls der letzten Nacht gegen die frueheren. [abstand] in bpm. Ohne
 * [urteil], solange weniger als drei fruehere Naechte da sind - der Schnitt
 * aus einer Nacht ist nur sie.
 */
internal data class Herzurteil(val frueher: Int, val schnitt: Double?, val urteil: Vergleich?, val abstand: Int)

/** Die HRV der letzten Nacht gegen ihr persoenliches Band. [abstand] in ms bis zum Bandrand, 0 im Band. */
internal data class Bandurteil(val urteil: Vergleich, val abstand: Int)

internal enum class Praeparatlage { ALLE, OFFEN, FAELLIG, KEINE }

internal data class Praeparatzeile(val genommen: Int, val faellig: Int, val lage: Praeparatlage, val namen: List<String>, val weitere: Int)

/** So viele Schlaege neben dem Schnitt heisst der Ruhepuls noch "as usual". */
internal const val RUHEPULS_SPANNE = 2
/** So viele Schlaege neben dem Schnitt der frueheren Tage heisst der wache Tagespuls hoeher oder tiefer. */
internal const val TAGESPULS_AB = 3
/** Ein Puls, der juenger ist, heisst "jetzt". */
internal const val PULS_FRISCH_S = 15 * 60L
/**
 * Erst Minutenwerte, die aelter sind, heissen "synced": die Uhr haelt die
 * Minutendaten 20 bis 35 Minuten zurueck (15er-Saetze plus 19 Minuten
 * unsicherer Schlaf), auch verbunden. Mit einer Viertelstunde stand der
 * Hinweis fast immer da.
 */
internal const val SYNC_ALT_S = 60 * 60L

internal fun Gesundheitsrechnung.tageszeit(uhrzeit: LocalTime): Tageszeit = when {
    uhrzeit < LocalTime.of(11, 0) -> Tageszeit.MORGEN
    uhrzeit < LocalTime.of(18, 0) -> Tageszeit.TAG
    else -> Tageszeit.ABEND
}

internal fun Gesundheitsrechnung.schlafurteil(minuten: Int, ziel: Int = Gesundheitsziele.SCHLAF_MIN): Schlafurteil = when {
    minuten >= ziel -> Schlafurteil.AUSGERUHT
    minuten >= ziel - 60 -> Schlafurteil.KURZ
    else -> Schlafurteil.SEHR_KURZ
}

/**
 * Der Satz oben auf der Seite, als Bausteine - die Worte legt die Ansicht
 * darum. Alles zu EINEM Zeitpunkt [jetzt]: Gruss, Belastung und versaeumte
 * Praeparate. [platz] benennt einen Platz ohne Namen ("Slot 3").
 */
internal fun Gesundheitsrechnung.tagesSatz(stand: GesundheitsStand, jetzt: LocalDateTime, platz: (Int) -> String): Tagessatzdaten {
    val n = stand.nacht
    // Ohne Abschnitte ist phasen[0] eine Null aus Unwissen, kein "nie wach".
    val wach = n?.takeIf { it.abschnitte.isNotEmpty() }?.phasen?.get(0)
    val offen = stand.supplemente?.takeIf { stand.mitSupCycle }?.let { s ->
        val z = praeparatzeile(s.heute(platz), jetzt.toLocalTime())
        // Nur Versaeumtes gehoert in den Satz - was erst spaeter ansteht, steht in der Liste.
        if (z.lage == Praeparatlage.OFFEN) z.namen.firstOrNull() else null
    }
    return Tagessatzdaten(
        tageszeit = tageszeit(jetzt.toLocalTime()),
        urteil = n?.let { schlafurteil(it.schlafMinuten) },
        schlafMinuten = n?.schlafMinuten,
        ruhe = n?.let { ruhepulsUrteil(it.ruhepuls, stand.ruhepulsWoche) },
        durchgeschlafen = wach != null && wach < Gesundheitsziele.DURCHGESCHLAFEN_MIN,
        schritte = stand.schritte,
        haerte = tagesurteil(stand.tagesherz, jetzt.toLocalDate())?.haerte,
        offen = offen,
        ohneDaten = stand.schritte == 0L && n == null && stand.puls.isEmpty(),
    )
}

/**
 * Wie immer heisst: hoechstens zwei Schlaege neben dem Schnitt der frueheren
 * Naechte. Entschieden wird am Abstand zum GERUNDETEN Schnitt, denn der steht
 * auf der Seite: bei 52.5 zeigt "Your usual" 53, und 55 sind dann 2 bpm
 * daneben, also "as usual" - nicht 2.5, gerundet 3, "above".
 */
internal fun Gesundheitsrechnung.ruhepulsUrteil(letzte: Int?, frueher: List<Int>): Herzurteil {
    val (naechte, schnitt) = naechteSchnitt(frueher)
    if (letzte == null || schnitt == null || naechte < Fenster.LERNNAECHTE) return Herzurteil(naechte, schnitt, null, 0)
    val gezeigt = schnitt.roundToInt()
    val abstand = abs(letzte - gezeigt)
    val urteil = when {
        abstand <= RUHEPULS_SPANNE -> Vergleich.WIE_IMMER
        letzte > gezeigt -> Vergleich.HOEHER
        else -> Vergleich.TIEFER
    }
    return Herzurteil(naechte, schnitt, urteil, abstand)
}

/**
 * Wieviele der frueheren Naechte zaehlen und ihr Schnitt: hoechstens die
 * letzten [Fenster.VERGLEICHSNAECHTE], ohne die ungemessenen (0). Fuer die HRV nur
 * die Zahl auf "Your usual" - ihr Urteil faellt am Band, siehe [hrvUrteil].
 */
internal fun Gesundheitsrechnung.naechteSchnitt(frueher: List<Int>): Pair<Int, Double?> {
    val werte = frueher.filter { it > 0 }.takeLast(Fenster.VERGLEICHSNAECHTE)
    return werte.size to Statistik.mittel(werte)
}

/**
 * Wie immer heisst: im Band der letzten Nacht ([Gesundheitsrechnung.hrvWoche],
 * der letzte Tag). Ein zweiter Massstab - zehn Prozent um den Schnitt von
 * sieben Naechten - widersprach der HRV-Karte: "in your range" darueber,
 * "below your usual" darunter. Ohne Wert oder Band kein Urteil.
 *
 * Der Abstand zaehlt bis zum Bandrand, nicht bis zur Mitte: der Satz sagt
 * "below your range", und auf 63 ms "as usual" folgt bei 64 ms "1 ms above"
 * statt eines Sprungs auf den Abstand zur Mitte. In ms, weil Band und Wert
 * auf der Karte in ms stehen - so laesst sich die Zahl dort nachrechnen.
 */
internal fun Gesundheitsrechnung.hrvUrteil(tag: Gesundheitsrechnung.HrvTag?): Bandurteil? {
    val wert = tag?.wert ?: return null
    val (unten, oben) = tag.band ?: return null
    // Was "im Band" heisst, entscheidet allein HrvTag.imBand - dieselbe Regel faerbt die Punkte im Bild.
    return when {
        tag.imBand == true -> Bandurteil(Vergleich.WIE_IMMER, 0)
        wert < unten -> Bandurteil(Vergleich.TIEFER, unten - wert)
        else -> Bandurteil(Vergleich.HOEHER, wert - oben)
    }
}

/** Der letzte Puls, und ob er juenger ist als eine Viertelstunde. */
internal fun Gesundheitsrechnung.pulsFrisch(sekunde: Long, jetzt: Long): Boolean = jetzt - sekunde <= PULS_FRISCH_S

/**
 * Ob der letzte Minutenwert der Uhr aelter ist als [SYNC_ALT_S]. Dann gilt
 * das "Jetzt" des Zeitstrahls nur bis dahin - eine getrennte Uhr liess einen
 * Stand von vor Stunden wie den jetzigen aussehen.
 */
internal fun Gesundheitsrechnung.syncAlt(letzteDaten: Instant?, jetzt: Instant): Boolean =
    letzteDaten != null && jetzt.epochSecond - letzteDaten.epochSecond > SYNC_ALT_S

/**
 * Wieviele Kalendertage [t] vor [jetzt] liegt: 0 heute, 1 gestern. Eine
 * Uhrzeit allein ("synced 9:14 PM") laese sich sonst als heute.
 */
internal fun Gesundheitsrechnung.tageZurueck(t: Instant, jetzt: Instant, zone: ZoneId): Long =
    ChronoUnit.DAYS.between(t.atZone(zone).toLocalDate(), jetzt.atZone(zone).toLocalDate())

/**
 * Die letzte Nacht und die frueheren. Letzte ist nur die, aus der heute
 * aufgewacht wurde: eine Stundenfrist ab dem Fensterbeginn liess nach einer
 * ausgelassenen Nacht die vorletzte als "Last night" gelten. Die frueheren
 * sind hoechstens [Fenster.VERGLEICHSNAECHTE] davor, ohne die letzte - sie wird mit
 * ihnen verglichen und zoege den Schnitt sonst zu sich.
 *
 * NUR AUS DER WOCHE DAVOR ([Fenster.VERGLEICHSNAECHTE] + 1 Tage): auch fuer eine
 * Nacht der Nachtansicht, die aus einem Monat Naechten blaettert. Nach zwei
 * Wochen ohne Uhr lernt Health wieder, und die Nachtansicht soll nicht mit
 * Naechten von damals einen Schnitt nennen.
 */
internal fun Gesundheitsrechnung.naechteTeilen(naechte: List<NachtEntity>, heute: LocalDate, zone: ZoneId): Pair<NachtEntity?, List<NachtEntity>> {
    val sortiert = naechte.sortedBy { it.beginnUtc }
    fun tag(n: NachtEntity) = nachtTag(n.beginnUtc, n.aufwachen, zone)
    val letzte = sortiert.lastOrNull { tag(it) <= heute }?.takeIf { tag(it) == heute }
    val ab = heute.minusDays(Fenster.VERGLEICHSNAECHTE + 1L)
    val frueher = sortiert.filter { it !== letzte && tag(it) < heute && !tag(it).isBefore(ab) }.takeLast(Fenster.VERGLEICHSNAECHTE)
    return letzte to frueher
}

/** Ohne Planzeit (aeltere SupCycle) gilt ein Praeparat ab dieser Stunde als versaeumt - morgens ist nichts versaeumt. */
internal const val PRAEPARATE_OHNE_PLAN_AB = 11

/** Versaeumt: nicht genommen und die Planzeit vorbei. EINE REGEL FUER WIDGET UND GESUNDHEITSSEITE. */
internal fun Gesundheitsrechnung.praeparatVersaeumt(e: SupCycle.Eintrag, jetzt: LocalTime): Boolean =
    !e.genommen && (e.zeit?.let { !jetzt.isBefore(it) } ?: (jetzt.hour >= PRAEPARATE_OHNE_PLAN_AB))

/**
 * Was bei den Praeparaten zu sagen ist. "Offen" (mit Warnfarbe) ist, was versaeumt ist
 * ([praeparatVersaeumt]), davor ist es schlicht faellig. [namen] nennt die offenen, sonst die faelligen.
 */
internal fun Gesundheitsrechnung.praeparatzeile(liste: List<SupCycle.Eintrag>, jetzt: LocalTime = LocalTime.now()): Praeparatzeile {
    val genommen = liste.count { it.genommen }
    val rest = liste.filterNot { it.genommen }
    val versaeumt = rest.filter { praeparatVersaeumt(it, jetzt) }
    val lage = when {
        liste.isEmpty() -> Praeparatlage.KEINE
        rest.isEmpty() -> Praeparatlage.ALLE
        versaeumt.isNotEmpty() -> Praeparatlage.OFFEN
        else -> Praeparatlage.FAELLIG
    }
    val gemeint = (if (lage == Praeparatlage.OFFEN) versaeumt else rest).map { it.name }
    val namen = gemeint.take(2)
    return Praeparatzeile(genommen, liste.size, lage, namen, gemeint.size - namen.size)
}

/**
 * Der Anteil jeder Phase an der Zeit im Bett in ganzen Prozent, je Index
 * wie [Nacht.phasen]. Alle fuenf zusammen 100: die groessten Reste bekommen
 * den Rest - einzeln gerundet stuenden bei drei gleich langen Phasen
 * 33 + 33 + 33 da.
 *
 * SCHLAF OHNE PHASE (Index 4) zaehlt mit, denn er ist Zeit im Bett, und die
 * steht ueber der Phasenkarte. Eine Zeile hat er dort nicht: liegt in einer
 * Nacht mit Phasen ein Stueck ohne, ergeben die vier Zeilen weniger als 100.
 */
internal fun Gesundheitsrechnung.phasenAnteile(phasen: IntArray): List<Int> {
    val summe = phasen.sum()
    if (summe == 0) return List(phasen.size) { 0 }
    val roh = phasen.map { it * 100.0 / summe }
    val ganz = roh.map { floor(it).toInt() }.toMutableList()
    roh.withIndex().sortedByDescending { it.value - floor(it.value) }.take(100 - ganz.sum()).forEach { ganz[it.index]++ }
    return ganz
}

/**
 * Tief und REM zusammen als Anteil der Zeit im Bett, 0..1 - das "restful"
 * des Widgets. Null ohne Phasen: eine Nacht nur aus Schlaf ohne Phase hat
 * keinen erholsamen Anteil, nicht einen von null.
 */
internal fun Gesundheitsrechnung.erholsamAnteil(phasen: IntArray): Double? {
    if (!mitPhasen(phasen)) return null
    // Index wie Nacht.phasen: 2 tief, 3 REM. Der Nenner wie in phasenAnteile, die Karte zeigt dieselbe Zeit im Bett.
    return (phasen[2] + phasen[3]).toDouble() / phasen.sum().coerceAtLeast(1)
}

/**
 * Der wache Tagespuls gegen den Schnitt der frueheren Tage
 * ([Gesundheitsrechnung.Tagesurteil.abstand], in bpm). Erst ab drei
 * Schlaegen daneben heisst er hoeher oder tiefer - eine Rechnung fuer die
 * Herzzeile und den Kopf der Herzseite.
 */
internal fun Gesundheitsrechnung.tagespulsUrteil(abstand: Int): Vergleich = when {
    abstand >= TAGESPULS_AB -> Vergleich.HOEHER
    abstand <= -TAGESPULS_AB -> Vergleich.TIEFER
    else -> Vergleich.WIE_IMMER
}
