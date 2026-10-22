package boulder.austausch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Was Boulder an den eigenen Server schickt (POST /sync). Der Server spiegelt es und beantwortet
 * daraus Claudes Fragen ueber MCP. Zeiten in UTC-Sekunden; [zone] sagt dem Server, in welcher
 * Ortszeit er sie zeigt.
 *
 * ERWEITERN NUR MIT VORGABEWERTEN: App und Server werden getrennt erneuert. Ein neues Feld ohne
 * Vorgabe liesse eine aeltere Gegenseite jede Lieferung ablehnen; unbekannte Felder uebergeht
 * [austauschJson] auf beiden Seiten.
 */
@Serializable
data class Lieferung(
    val fassung: Int = FASSUNG,
    val gesendet: Long,
    val zone: String,
    val app: String,
    val tage: List<Tag> = emptyList(),
    val naechte: List<Nacht> = emptyList(),
    val trainings: List<Training> = emptyList(),
    val minuten: List<Minute> = emptyList(),
    val stand: Stand? = null,
    /**
     * Die Kennungen gesendeter Trainings, die Boulder nicht mehr fuehrt: in der Akte geloescht
     * oder einem Doppel aus einer anderen App gewichen. Der Server loescht sie.
     */
    val trainingsEntfernt: List<String> = emptyList(),
) {
    companion object {
        const val FASSUNG = 1
    }
}

/**
 * Die Tageswerte wie in Trends und Sicherung: Spalte ("schritte", "schlaf" ...) -> Wert.
 *
 * DER SERVER FUEHRT ZUSAMMEN: [werte] ersetzen gespeicherte Werte gleichen Namens, die Spalten in
 * [entfernt] fallen weg, alle anderen bleiben. Boulder kennt nicht jede Spalte jedes Tages - Wasser
 * und Koffein liest es nur, solange der Tag im Fenster der Akte liegt und die Akte antwortet.
 */
@Serializable
data class Tag(
    val datum: String,
    val werte: Map<String, Double>,
    /** Die Spalten, die Boulder fuer diesen Tag sicher kennt und die es nicht (mehr) gibt. */
    val entfernt: List<String> = emptyList(),
)

/** Eine Nacht der Firmware; Minuten ab [beginn], wie in der App. */
@Serializable
data class Nacht(
    val beginn: Long,
    val utcZuLokal: Int,
    val einschlafen: Int,
    val aufwachen: Int,
    val schlafMinuten: Int,
    val ruhepuls: Int,
    val hrv: Int,
    val messstufe: Int? = null,
    val akkuBeginn: Int? = null,
    val akkuEnde: Int? = null,
    val abschnitte: List<Abschnitt> = emptyList(),
    val hrvFenster: List<HrvFenster> = emptyList(),
    /** Hing die Uhr nachts am Ladegeraet? Dann sagt der Akku nichts ueber den Verbrauch. */
    val geladen: Boolean = false,
)

/** [phase] wie SchlafPhase der Firmware: 0 wach, 1 leicht, 2 tief, 3 REM, 4 Schlaf ohne Phase. */
@Serializable
data class Abschnitt(val von: Int, val bis: Int, val phase: Int)

/** Fuenf Minuten ab [minute] (ab Beginn der Nacht): RMSSD in ms, Puls je Minute (0 = unbekannt). */
@Serializable
data class HrvFenster(val minute: Int, val rmssd: Int, val puls: Int)

@Serializable
data class Training(
    val kennung: String,
    val sport: String,
    val beginn: Long,
    val ende: Long,
    val aktivSekunden: Long,
    val meter: Double = 0.0,
    val pulsMittel: Int? = null,
    val pulsMax: Int? = null,
    /** Von Boulder oder Kiesel-Helper aufgezeichnet, nicht von einer fremden App. */
    val eigen: Boolean = true,
)

@Serializable
data class Minute(val zeit: Long, val schritte: Int, val vmc: Int, val puls: Int, val lage: Int)

@Serializable
data class Stand(
    val firmware: String? = null,
    /** "schritte", "schlafMinuten". */
    val ziele: Map<String, Int> = emptyMap(),
    val maxPuls: Int? = null,
)

/** Die Antwort auf eine Lieferung: wie viele Eintraege der Server uebernommen hat. */
@Serializable
data class Quittung(
    val tage: Int,
    val naechte: Int,
    val trainings: Int,
    val minuten: Int,
    /**
     * Wie viele Trainings der Server entfernen sollte ([Lieferung.trainingsEntfernt]); null bei einem
     * Server vor 0.56.0, der das Feld nicht kennt. Dann bleiben sie bei Boulder vorgemerkt und gehen
     * wieder mit, bis ein neuerer Server sie entfernt.
     */
    val trainingsEntfernt: Int? = null,
)

val austauschJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
