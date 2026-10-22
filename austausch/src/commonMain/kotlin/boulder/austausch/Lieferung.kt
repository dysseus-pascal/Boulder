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
    /** Saetze aus Health Connect, neu oder geaendert, aus jeder App ([Akteneintrag]). */
    val akte: List<Akteneintrag> = emptyList(),
    /** Die Kennungen von Saetzen, die in Health Connect geloescht wurden. Der Server loescht sie. */
    val akteEntfernt: List<String> = emptyList(),
    /**
     * Tage der Vorgeschichte: aus Sicherungen zurueckgeholt, vor allem aus Kiesel-Helper, und anders
     * gemessen als Boulder. Der Server ersetzt einen solchen Tag ganz.
     */
    val vorgeschichte: List<Tag> = emptyList(),
    val sauerstoff: List<Sauerstoff> = emptyList(),
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

/** Eine SpO2-Messung der Uhr; [qualitaet] 0-7, wie die Firmware sie meldet. */
@Serializable
data class Sauerstoff(val zeit: Long, val prozent: Int, val qualitaet: Int)

/**
 * Ein Satz aus Health Connect, gleich welcher Art und aus welcher App. Allgemein gehalten, damit
 * Format und Server nicht je Satzart wachsen. Die Namen in [werte], [texte] und [abschnitte] sind
 * englisch: der Server zeigt sie Claude so.
 */
@Serializable
data class Akteneintrag(
    /** Die Kennung in Health Connect (metadata.id); ein geaenderter Satz behaelt sie. */
    val id: String,
    /** Die Satzart ohne "Record", etwa "Weight", "HeartRate", "SleepSession". */
    val art: String,
    /** UTC-Sekunden; bei einer Messung zu einem Zeitpunkt dieser. */
    val beginn: Long,
    val ende: Long? = null,
    /** Ortszeit minus UTC beim Beginn in Sekunden, wenn die schreibende App sie angab. */
    val versatz: Int? = null,
    /** Paketname der schreibenden App. */
    val quelle: String = "",
    val werte: Map<String, Double> = emptyMap(),
    val texte: Map<String, String> = emptyMap(),
    /** Messreihen in einem Satz: Puls, Geschwindigkeit, Leistung, Kadenz ... */
    val proben: List<Probe> = emptyList(),
    /** Schlafphasen, Abschnitte und Runden eines Trainings. */
    val abschnitte: List<Spanne> = emptyList(),
)

/** Ein Wert einer Messreihe zur UTC-Sekunde [t]. */
@Serializable
data class Probe(val t: Long, val w: Double)

/** Eine Zeitspanne in einem Satz, etwa eine Schlafphase ([art] "deep") oder eine Runde. */
@Serializable
data class Spanne(val von: Long, val bis: Long, val art: String, val wert: Double? = null)

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
    /**
     * Die Zahlen zu den Listen, die erst 0.57.0 kennt. Null heisst: ein aelterer Server, der sie
     * uebergeht; Boulder haelt sie dann zurueck und schickt sie einem neueren noch einmal.
     */
    val akte: Int? = null,
    val akteEntfernt: Int? = null,
    val vorgeschichte: Int? = null,
    val sauerstoff: Int? = null,
)

val austauschJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
