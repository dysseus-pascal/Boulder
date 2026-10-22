package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import coredevices.pebble.kiesel.Sport
import theme.lightScheme
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Die Farben der App - EINE Stelle je Groesse. Vorher hatte die HRV drei
 * Farben (gruen, rot, violett), der Ruhepuls zwei Rottoene, und feste
 * Hexwerte ohne Hell-Variante kamen auf der hellen Karte unter 3:1.
 *
 * Die Regeln:
 * - Dieselbe Groesse hat ueberall dieselbe [Farbe]: Saeule in den Trends,
 *   Punkt im Vergleich, Kurve in der Nacht, Zeichen in der Liste.
 * - [Farbe.flaeche] fuellt Flaechen (Saeulen, Balken, Baender, Grund der
 *   Tonkreise, Legendenpunkte). [Farbe.ton] traegt Schrift, Linien und kleine
 *   Punkte und haelt auf der Karte mindestens 3:1 (FarbenTest rechnet nach).
 * - Hell oder dunkel entscheidet die Luminanz des App-Schemas, nicht das
 *   Telefon: Boulder kennt Light, Dark, Black und System.
 * - Auf Kartenkacheln (OSM) immer die Flaeche - die Kacheln sind in beiden
 *   Schemata hell, ein abgedunkelter Ton saehe dort aus wie eine Bahnlinie.
 * - Urteile (gut, warnung, schlecht) faerben nur das Urteil, nie die Groesse.
 */

/**
 * Die Farbe einer Groesse: [flaeche] fuer Flaechen, [hell] und [dunkel] fuer
 * Schrift und Striche auf der hellen bzw. dunklen Karte. Die Flaeche darf je
 * Schema verschieden sein ([flaecheHell], [flaecheDunkel]) - beim Schlaf
 * waere das Dunkelblau auf dem dunklen Grau sonst kaum zu sehen.
 */
internal class Farbe(
    val flaecheHell: Color,
    val flaecheDunkel: Color,
    val hell: Color,
    val dunkel: Color,
) {
    /** Eine Flaeche fuer beide Schemata - der haeufige Fall. */
    constructor(flaeche: Color, hell: Color, dunkel: Color) : this(flaeche, flaeche, hell, dunkel)

    /** Die Flaeche im hellen ([dunkelSchema] = false) oder dunklen Schema - fuer Rechnungen ausserhalb von Compose. */
    fun flaecheIm(dunkelSchema: Boolean): Color = if (dunkelSchema) flaecheDunkel else flaecheHell

    /** Der Ton fuer Schrift und Striche im hellen oder dunklen Schema - fuer Rechnungen ausserhalb von Compose. */
    fun tonIm(dunkelSchema: Boolean): Color = if (dunkelSchema) dunkel else hell
}

/** Der Ton fuer Schrift, Linien und kleine Punkte im aktuellen Schema - mindestens 3:1 auf der Karte. */
@Composable
internal fun Farbe.ton(): Color = tonIm(Farben.dunkel())

/** Die Flaechenfarbe im aktuellen Schema - fuer Saeulen, Balken, Baender und Tonkreise. */
@Composable
internal fun Farbe.flaeche(): Color = flaecheIm(Farben.dunkel())

/**
 * Die Farbe mit [anteil] deckend ueber der Kartenflaeche gemischt. Fuer
 * blasse Striche und Saeulen statt Alpha: mit Alpha laege ein Strich im
 * Punkt doppelt und dunkelte ihn fleckig, und der Kontrast liesse sich
 * nicht nachrechnen.
 */
@Composable
internal fun Color.aufKarte(anteil: Float): Color = copy(alpha = anteil).compositeOver(Farben.karte())

internal object Farben {

    // Hier im Objekt, nicht auf Dateiebene: sonst stiesse das Laden von Farben das der Datei an und umgekehrt.
    private fun c(argb: Long) = Color(argb)

    // --- Groessen ---------------------------------------------------------------

    /** Schritte, aktive Minuten, Strecke im Zeitstrahl - alles, was Bewegung zaehlt. */
    val bewegung = Farbe(c(0xFF4F8A3A), hell = c(0xFF3B6E2B), dunkel = c(0xFF9BD0A6))

    /**
     * Schlafdauer und Naechte. DIE FLAECHE JE SCHEMA: das Dunkelblau traegt
     * auf der hellen Karte, auf der dunklen kam es auf 2:1 - dort das Hellblau,
     * das die Nachtansicht schon fuer ihre Saeulen nahm.
     */
    val schlaf = Farbe(flaecheHell = c(0xFF2F5597), flaecheDunkel = c(0xFF7FA7D9), hell = c(0xFF2F5597), dunkel = c(0xFF7FA7D9))

    /** Puls, Ruhepuls, Tagespuls - das Herzrot. */
    val puls = Farbe(c(0xFFC0392B), hell = c(0xFFB03427), dunkel = c(0xFFE8776C))

    /** Der Blutdruck gehoert zum Herzen und traegt dessen Rot (Herzzeile, Herzseite). */
    val blutdruck = puls

    /**
     * Die HRV - VIOLETT, nicht Herzrot: in "Heart trends" stehen Ruhepuls und
     * HRV untereinander und sollen sich unterscheiden. Das Urteil am Band
     * ([imBand], [ausserBand]) bleibt davon getrennt.
     */
    val hrv = Farbe(c(0xFF9B59B6), hell = c(0xFF8E44AD), dunkel = c(0xFFC98BD6))

    /** Blutsauerstoff - ein Petrol, das auf beiden Karten ueber 3:1 bleibt. */
    val spo2 = Farbe(c(0xFF2E7C8A), hell = c(0xFF2E7C8A), dunkel = c(0xFF2E7C8A))

    val wasser = Farbe(c(0xFF3A86C8), hell = c(0xFF2B6FA8), dunkel = c(0xFF6FA9DC))

    val koffein = Farbe(c(0xFF8A5A2B), hell = c(0xFF7A4E22), dunkel = c(0xFFD09A62))

    /** Praeparate: die Flaeche ein helles Gruen, Schrift und Haken im Ton der Bewegung. */
    val praeparat = Farbe(c(0xFF7DBB8A), hell = bewegung.hell, dunkel = bewegung.dunkel)

    /** Gewicht und Zusammensetzung - Violett war unter den Gesundheitsfarben noch frei. */
    val koerper = Farbe(c(0xFF7E57C2), hell = c(0xFF5E35B1), dunkel = c(0xFFB39DDB))

    /** Der Akkuverlauf der Uhr. Der Stand als Urteil steht in [akkuStufe]. */
    val akku = Farbe(c(0xFF2CB0C6), hell = c(0xFF1B7F90), dunkel = c(0xFF2CB0C6))

    // --- Urteile ------------------------------------------------------------------

    /** Besser, im Rahmen - das Gruen des Schemas (colorScheme.primary), damit Urteil und Knopf eins sind. */
    val gut = Farbe(c(0xFF7DBB8A), hell = c(0xFF4E7D5B), dunkel = c(0xFF7DBB8A))

    /** Versaeumt, kurz, schlechter - Bernstein. */
    val warnung = Farbe(c(0xFFE0A33A), hell = c(0xFF8A5F00), dunkel = c(0xFFE0A33A))

    /** Knapp oder leer - ein Rot, das sich vom Herzrot abhebt. */
    val schlecht = Farbe(c(0xFFF2867A), hell = c(0xFFB3261E), dunkel = c(0xFFF2867A))

    /**
     * Das App-Gruen fuer Taten (Knoepfe), KEIN Urteil und KEINE Groesse: die
     * Flaeche ist colorScheme.primary. Die Schrift auf dem blassen Knopfgrund
     * ist dasselbe Gruen, nur so weit vertieft (hell, 3E6449) bzw. aufgehellt
     * (dunkel, 90C59C), dass sie 4,5:1 haelt - das Markengruen selbst kaeme
     * dort auf 3,4:1 bzw. 4,4:1.
     */
    val aktion = Farbe(flaecheHell = c(0xFF4E7D5B), flaecheDunkel = c(0xFF7DBB8A), hell = c(0xFF3E6449), dunkel = c(0xFF90C59C))

    /**
     * Die Akkuzahl nach Stand, wie der Nutzer sie festlegte: gruen ab 50 %,
     * gelb ab 20 %, darunter rot. Das Gruen ist das der Bewegung (so stand
     * es seit 0.34.1 im Satz).
     */
    fun akkuStufe(prozent: Int): Farbe = when {
        prozent >= 50 -> bewegung
        prozent >= 20 -> warnung
        else -> schlecht
    }

    // --- Herzseite (Google Health, Nutzerentscheid 0.38.0) -------------------------

    /** Im persoenlichen Band (HRV, Blutdruck oben und unten) - Punkte, Band und das Urteil "In your range". */
    val imBand = Farbe(c(0xFF5BD47A), hell = c(0xFF2E7D32), dunkel = c(0xFF5BD47A))

    /** Neben dem Band (HRV, Blutdruck) - Punkte und Urteil. */
    val ausserBand = Farbe(c(0xFFF6BF26), hell = c(0xFFB26A00), dunkel = c(0xFFF6BF26))

    /** So deckend liegt das Band hinter den Punkten - blass genug, dass die Linie darueber steht. */
    const val BAND_DECKUNG = 0.16f

    /** Pulszonen der Herzseite: leicht, moderat, kraeftig, Spitze - die Farben von Google Health. */
    val pulszonen = listOf(
        Farbe(c(0xFF4FC3F7), hell = c(0xFF0277BD), dunkel = c(0xFF4FC3F7)),
        Farbe(c(0xFF5BD47A), hell = c(0xFF2E7D32), dunkel = c(0xFF5BD47A)),
        Farbe(c(0xFFF6BF26), hell = c(0xFFB26A00), dunkel = c(0xFFF6BF26)),
        Farbe(c(0xFFEF6B5E), hell = c(0xFFC62828), dunkel = c(0xFFEF6B5E)),
    )

    // --- Training ---------------------------------------------------------------------

    /**
     * Die Trainingszonen unter Zone 1 bis Zone 5 - DIESELBEN WIE AUF DER UHR
     * (Kieselsport, thema.c): wer nach dem Lauf auf die Uhr schaut, findet
     * dieselbe Farbe. Die Flaeche ist die Uhrfarbe; fuer Schrift und Striche
     * auf der hellen Karte abgedunkelt wie die Sportarten.
     */
    val trainingszonen: List<Farbe> = listOf(
        0xFFBDBDBD, // unter Zone 1
        0xFF55AAFF, // PictonBlue
        0xFF00AA55, // JaegerGreen
        0xFFAAAA00, // Limerick
        0xFFFFAA00, // ChromeYellow
        0xFFFF0055, // Folly
    ).map { abgedunkelt(c(it)) }

    /**
     * Die Toene der Sportarten aus Kiesel-Helper, KRAEFTIGER: auf Boulders
     * Grau wirkten die gedeckten Erdfarben ausgewaschen. MTB EIN DUNKLERES
     * OCKER als Bike: beide zaehlen als eigene Art, und im Wochenstapel lagen
     * zwei gleiche Schichten ohne Grenze.
     */
    private val sportarten: Map<Sport, Farbe> = mapOf(
        Sport.Laufen to 0xFFE8622E,
        Sport.Bike to 0xFFE2A422,
        Sport.Mtb to 0xFFB8792A,
        Sport.Wandern to 0xFF8DB53C,
        Sport.Kraft to 0xFFD9453A,
        Sport.Yoga to 0xFFA85CB4,
        Sport.Schwimmen to 0xFF2CB0C6,
        Sport.Atem to 0xFF5B7CE0,
        Sport.Anderes to 0xFF9C8B7C,
    ).mapValues { abgedunkelt(c(it.value)) }

    /** Die Farbe einer Sportart: Kreis, Stapel, Karte - und abgedunkelt fuer Schrift auf heller Karte. */
    fun sport(s: Sport): Farbe = sportarten.getValue(s)

    /**
     * Eine kraeftige Farbe, die im dunklen Schema selbst traegt; im hellen um
     * 45 % zu Schwarz gemischt - Gelb und Gruen kaemen auf der hellen Karte
     * sonst unter 3:1.
     */
    private fun abgedunkelt(f: Color) = Farbe(f, hell = lerp(f, Color.Black, 0.45f), dunkel = f)

    /**
     * Die Tempo-Skala: langsam blau, schnell rot - ueber Cyan, Gruen und Gelb,
     * damit man die Mitte sieht. [anteil] 0 = langsam, 1 = schnell.
     * DIE FLAECHE fuer OSM-Kacheln und die Farblegende darunter; DER TON fuer
     * Kurven und Bahnpunkte: dunkel um 15 % aufgehellt (das Blau kam auf dem
     * Grau auf 2,9:1), hell IM SELBEN FARBTON NUR SO DUNKEL WIE NOETIG fuer
     * 3:1 (das Gelb kam auf F5F5F5 auf 1,2:1). Fest um 45 % zu Schwarz wurden
     * Blau, Gelb, Orange und Rot zu Marine, Oliv, Braun und Dunkelrot: die
     * Kurve passte nicht mehr zur Legende, Braun und Dunkelrot waren kaum zu
     * trennen. Jetzt bleiben Blau und Rot, wie sie sind. In einer Zeichnung
     * ohne Compose: [Farbe.tonIm] mit einem vorher gelesenen [dunkel].
     */
    fun tempo(anteil: Float): Farbe {
        val a = anteil.coerceIn(0f, 1f)
        val farbton = 220f - 220f * a
        val f = Color.hsv(farbton, 0.85f, 0.9f)
        // Die dunklere der zwei naechsten Stufen: so haelt auch ein Ton zwischen zwei Stufen 3:1.
        val stufe = a * TEMPO_STUFEN
        val v = minOf(tempoHelligkeit[floor(stufe).toInt()], tempoHelligkeit[ceil(stufe).toInt()])
        return Farbe(f, hell = Color.hsv(farbton, 0.85f, v), dunkel = lerp(f, Color.White, 0.15f))
    }

    private const val TEMPO_STUFEN = 100

    /**
     * Je Stufe der Skala die hoechste Helligkeit (HSV-Wert), mit der der Ton
     * auf der hellen Karte noch 3,1:1 haelt - 0,1 Luft ueber der Regel.
     * EINMAL GERECHNET: die Kurve fragt je Linienstueck, oft tausendfach je Bild.
     */
    private val tempoHelligkeit: FloatArray by lazy {
        val grund = minOf(lightScheme.surface.luminance(), lightScheme.surfaceContainerHigh.luminance())
        FloatArray(TEMPO_STUFEN + 1) { k ->
            val farbton = 220f - 220f * k / TEMPO_STUFEN
            var v = 0.9f
            while (v > 0.3f && (grund + 0.05f) / (Color.hsv(farbton, 0.85f, v).luminance() + 0.05f) < 3.1f) v -= 0.01f
            v
        }
    }

    // --- Schlafphasen -----------------------------------------------------------------

    /**
     * Die Phasen als Flaechen, in der Reihenfolge der Firmware: wach, leicht,
     * tief, REM, Schlaf ohne Phase. IN BEIDEN SCHEMATA GLEICH - das
     * Hypnogramm soll hell wie dunkel dasselbe Bild sein, und die
     * Legendenpunkte muessen zu den Bloecken passen. Keine Schrift in diesen
     * Farben; fuer Text die [schlaf]-Toene.
     */
    val phasen: List<Color> = listOf(c(0xFFE0A33A), c(0xFF7FA7D9), c(0xFF2F5597), c(0xFF9B6BC9), c(0xFF6C8EBF))

    const val WACH = 0
    const val LEICHT = 1
    const val TIEF = 2
    const val REM = 3
    const val OHNE_PHASE = 4

    // --- Aus dem Schema ---------------------------------------------------------------

    /** Ist das Schema der App dunkel? Aus der Luminanz des Hintergrunds - nie aus dem Telefon. */
    @Composable
    fun dunkel(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /**
     * Die Kartenflaeche, DUNKLER ALS DIE SEITE, NICHT HELLER. Materials Vorgabe
     * legt die Karte auf die hellste Flaeche des Schemas - auf Boulders Grau
     * ergab das hellgraue Kaesten mit blassen Farben darin. Im hellen Schema
     * bleibt sie hell, und der Rand trennt sie vom Weiss.
     */
    @Composable
    fun karte(): Color = if (dunkel()) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh

    /** Ohne Urteil: das Grau der Nebenschrift. */
    @Composable
    fun neutral(): Color = MaterialTheme.colorScheme.onSurfaceVariant

    /** Rasterlinien der Bilder - durchgezogen; gestrichelt heisst bei Boulder "Schnitt". */
    @Composable
    fun raster(): Color = MaterialTheme.colorScheme.outlineVariant

    /**
     * Was noch kommt, blass hinterlegt (das Tagesband rechts vom Jetzt). Aus
     * der Schrift abgeleitet wie [spur], aber halb so deckend: die Zukunft
     * soll zuruecktreten, nicht wie eine leere Saeule aussehen.
     */
    @Composable
    fun zukunft(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)

    /**
     * Die leere Spur hinter einer Saeule. Neutral aus der Schrift abgeleitet:
     * surfaceVariant ist im hellen Schema ein Lila, das sonst nirgends vorkommt.
     */
    @Composable
    fun spur(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
}

/** Die Farben einer Karte des [Kasten]s - siehe [Farben.karte]. */
@Composable
internal fun kartenFarben(): CardColors = CardDefaults.cardColors(containerColor = Farben.karte())

/** Der feine Rand, der die Karte von der Seite trennt. */
@Composable
internal fun kartenRand(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
