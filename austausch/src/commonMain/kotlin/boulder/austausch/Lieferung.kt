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
) {
    companion object {
        const val FASSUNG = 1
    }
}

/** Die Tageswerte wie in Trends und Sicherung: Spalte ("schritte", "schlaf" ...) -> Wert. */
@Serializable
data class Tag(val datum: String, val werte: Map<String, Double>)

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
data class Quittung(val tage: Int, val naechte: Int, val trainings: Int, val minuten: Int)

val austauschJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
