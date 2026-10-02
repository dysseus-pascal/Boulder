package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Die Worte der Gesundheitsseite - welches Urteil, welcher Satz, welche
 * Zeile. Hier und nicht in der Ansicht: die Schwellen stehen in der Fussnote
 * der Seite, und was dort versprochen wird, muss ein Test halten.
 */

internal enum class Schlafurteil { AUSGERUHT, KURZ, SEHR_KURZ }

/** Morgens zaehlt die Nacht, tagsueber der Rest zum Ziel, abends die Summe. */
internal enum class Tageszeit { MORGEN, TAG, ABEND }

internal enum class Schrittlage { BEGONNEN, NOCH, ERREICHT, ANTEIL }

internal data class Tagessatz(
    val tageszeit: Tageszeit,
    /** Null ohne Nacht - dann spricht der Satz nur von Schritten. */
    val urteil: Schlafurteil?,
    val durchgeschlafen: Boolean,
    /** Null, wenn die Abschnitte der Nacht fehlen - dann ist ueber Wachsein nichts bekannt. */
    val wachMinuten: Int?,
    /** Minuten ueber (positiv) oder unter dem Schlafziel. */
    val schlafAbstand: Int?,
    val schritte: Long,
    val schrittlage: Schrittlage,
    val schritteFehlen: Long,
    val zielProzent: Int,
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

internal enum class Tagesmerkmal { HEUTE, MEISTE, ERSTER, ZIEL, KEINS }

internal data class Schritttag(val tag: LocalDate, val schritte: Long, val merkmal: Tagesmerkmal)

internal data class Schrittwoche(val tage: List<Schritttag>, val seit: LocalDate?, val volleWocheAb: LocalDate?)

internal const val SCHRITTZIEL = 10_000L
internal const val SCHLAFZIEL_MIN = 8 * 60
/**
 * Ab so vielen frueheren Naechten gibt es ein Urteil ueber den Ruhepuls und
 * einen Schnitt auf "Your usual". Die HRV urteilt nicht hiernach, sondern an
 * ihrem Band - und das braucht [Gesundheitsrechnung.HRV_MINDESTENS] Naechte.
 */
internal const val LERNNAECHTE = 3
/** Hoechstens so viele fruehere Naechte bilden den Schnitt. */
internal const val VERGLEICHSNAECHTE = 7
internal const val RUHEPULS_SPANNE = 2
/** Weniger Wachminuten gelten als durchgeschlafen. */
internal const val DURCHGESCHLAFEN_MIN = 10
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

internal fun Gesundheitsrechnung.schlafurteil(minuten: Int, ziel: Int = SCHLAFZIEL_MIN): Schlafurteil = when {
    minuten >= ziel -> Schlafurteil.AUSGERUHT
    minuten >= ziel - 60 -> Schlafurteil.KURZ
    else -> Schlafurteil.SEHR_KURZ
}

/** Der Satz oben auf der Seite, als Bausteine - die Worte legt die Ansicht darum. */
internal fun Gesundheitsrechnung.tagesSatz(stand: GesundheitsStand, uhrzeit: LocalTime): Tagessatz {
    val zeit = tageszeit(uhrzeit)
    val n = stand.nacht
    // Ohne Abschnitte ist phasen[0] eine Null aus Unwissen, kein "nie wach".
    val wach = n?.takeIf { it.abschnitte.isNotEmpty() }?.phasen?.get(0)
    val lage = when {
        stand.schritte >= SCHRITTZIEL -> Schrittlage.ERREICHT
        zeit == Tageszeit.MORGEN -> Schrittlage.BEGONNEN
        zeit == Tageszeit.TAG -> Schrittlage.NOCH
        else -> Schrittlage.ANTEIL
    }
    return Tagessatz(
        tageszeit = zeit,
        urteil = n?.let { schlafurteil(it.schlafMinuten) },
        durchgeschlafen = wach != null && wach < DURCHGESCHLAFEN_MIN,
        wachMinuten = wach,
        schlafAbstand = n?.let { it.schlafMinuten - SCHLAFZIEL_MIN },
        schritte = stand.schritte,
        schrittlage = lage,
        schritteFehlen = (SCHRITTZIEL - stand.schritte).coerceAtLeast(0),
        zielProzent = (stand.schritte * 100 / SCHRITTZIEL).toInt(),
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
    if (letzte == null || schnitt == null || naechte < LERNNAECHTE) return Herzurteil(naechte, schnitt, null, 0)
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
 * letzten [VERGLEICHSNAECHTE], ohne die ungemessenen (0). Fuer die HRV nur
 * die Zahl auf "Your usual" - ihr Urteil faellt am Band, siehe [hrvUrteil].
 */
internal fun Gesundheitsrechnung.naechteSchnitt(frueher: List<Int>): Pair<Int, Double?> {
    val werte = frueher.filter { it > 0 }.takeLast(VERGLEICHSNAECHTE)
    return werte.size to werte.takeIf { it.isNotEmpty() }?.average()
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
    return when {
        wert < unten -> Bandurteil(Vergleich.TIEFER, unten - wert)
        wert > oben -> Bandurteil(Vergleich.HOEHER, wert - oben)
        else -> Bandurteil(Vergleich.WIE_IMMER, 0)
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
 * sind hoechstens [VERGLEICHSNAECHTE] davor, ohne die letzte - sie wird mit
 * ihnen verglichen und zoege den Schnitt sonst zu sich.
 *
 * NUR AUS DER WOCHE DAVOR ([VERGLEICHSNAECHTE] + 1 Tage): auch fuer eine
 * Nacht der Nachtansicht, die aus einem Monat Naechten blaettert. Nach zwei
 * Wochen ohne Uhr lernt Health wieder, und die Nachtansicht soll nicht mit
 * Naechten von damals einen Schnitt nennen.
 */
internal fun Gesundheitsrechnung.naechteTeilen(naechte: List<NachtEntity>, heute: LocalDate, zone: ZoneId): Pair<NachtEntity?, List<NachtEntity>> {
    val sortiert = naechte.sortedBy { it.beginnUtc }
    fun tag(n: NachtEntity) = nachtTag(n.beginnUtc, n.aufwachen, zone)
    val letzte = sortiert.lastOrNull { tag(it) <= heute }?.takeIf { tag(it) == heute }
    val ab = heute.minusDays(VERGLEICHSNAECHTE + 1L)
    val frueher = sortiert.filter { it !== letzte && tag(it) < heute && !tag(it).isBefore(ab) }.takeLast(VERGLEICHSNAECHTE)
    return letzte to frueher
}

/**
 * Was bei den Praeparaten zu sagen ist. Erst nach dem ersten Haken heisst
 * der Rest "offen" (und bekommt die Warnfarbe); vorher ist er schlicht
 * faellig - morgens um sieben ist nichts versaeumt.
 */
internal fun Gesundheitsrechnung.praeparatzeile(liste: List<SupCycle.Eintrag>): Praeparatzeile {
    val genommen = liste.count { it.genommen }
    val offen = liste.filterNot { it.genommen }.map { it.name }
    val lage = when {
        liste.isEmpty() -> Praeparatlage.KEINE
        offen.isEmpty() -> Praeparatlage.ALLE
        genommen == 0 -> Praeparatlage.FAELLIG
        else -> Praeparatlage.OFFEN
    }
    val namen = if (offen.size <= 2) offen else offen.take(2)
    return Praeparatzeile(genommen, liste.size, lage, namen, offen.size - namen.size)
}

/**
 * Die Tage der Schrittliste, der juengste zuerst. Gezaehlt wird ab dem
 * ersten Tag mit Schritten: davor war die Uhr nicht da, und eine Null dort
 * waere eine Behauptung. Solange die Woche nicht voll ist, sagt die Seite,
 * ab wann sie es ist.
 */
internal fun Gesundheitsrechnung.schrittwoche(woche: List<Pair<LocalDate, Long>>, ziel: Long = SCHRITTZIEL): Schrittwoche {
    if (woche.isEmpty()) return Schrittwoche(emptyList(), null, null)
    val heute = woche.last().first
    val ersterIndex = woche.indexOfFirst { it.second > 0 }.takeIf { it >= 0 } ?: woche.lastIndex
    val gezaehlt = woche.drop(ersterIndex)
    val voll = ersterIndex == 0
    val vortage = gezaehlt.dropLast(1).filter { it.second > 0 }
    val meiste = vortage.takeIf { it.size >= 2 }?.maxBy { it.second }?.first
    val erster = gezaehlt.first().first.takeIf { !voll && it != heute }
    val tage = gezaehlt.map { (tag, schritte) ->
        val merkmal = when {
            tag == heute -> Tagesmerkmal.HEUTE
            tag == meiste -> Tagesmerkmal.MEISTE
            tag == erster -> Tagesmerkmal.ERSTER
            schritte >= ziel -> Tagesmerkmal.ZIEL
            else -> Tagesmerkmal.KEINS
        }
        Schritttag(tag, schritte, merkmal)
    }.asReversed()
    val seit = gezaehlt.first().first.takeIf { !voll }
    return Schrittwoche(tage, seit, seit?.plusDays(woche.size - 1L))
}

/**
 * Tief, REM und leicht in ganzen Prozent, die zusammen 100 ergeben: die
 * groessten Reste bekommen den Rest - sonst stuenden 16 + 28 + 55 da.
 */
internal fun Gesundheitsrechnung.phasenAnteile(phasen: IntArray): List<Int> {
    val werte = listOf(phasen[2], phasen[3], phasen[1])
    val summe = werte.sum()
    if (summe == 0) return listOf(0, 0, 0)
    val roh = werte.map { it * 100.0 / summe }
    val ganz = roh.map { floor(it).toInt() }.toMutableList()
    roh.withIndex().sortedByDescending { it.value - floor(it.value) }.take(100 - ganz.sum()).forEach { ganz[it.index]++ }
    return ganz
}

/**
 * Wieviel vom Ring gefuellt ist, und was darueber hinausgeht - der
 * Ueberlauf als zweite Runde, hoechstens eine volle.
 */
internal fun Gesundheitsrechnung.ringAnteil(wert: Double, ziel: Double): Pair<Float, Float> {
    if (ziel <= 0) return 0f to 0f
    val anteil = (wert / ziel).coerceAtLeast(0.0)
    return anteil.coerceAtMost(1.0).toFloat() to (anteil - 1).coerceIn(0.0, 1.0).toFloat()
}
