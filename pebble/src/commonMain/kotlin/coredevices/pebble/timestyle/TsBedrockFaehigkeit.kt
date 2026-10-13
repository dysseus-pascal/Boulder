package coredevices.pebble.timestyle

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.pebble.firmware.ReleaseTagVersion
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BytesWatchPref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Ab diesem Firmware-Build gibt es TimeStyle Bedrock auf der Uhr ... */
const val TS_BEDROCK_AB_BUILD = 22

/** ... ab diesem nimmt sie seine Einstellungen an (Schluessel "tsBedrock") ... */
const val TS_BEDROCK_EINSTELLUNGEN_AB_BUILD = 23

/**
 * ... und ab diesem liest sie die Schrift (Byte 6), alle 37 Sprachen und den
 * UV-Wert der Stunde. MUSS die Nummer des Firmware-Releases sein, das diese
 * Teile bringt (Firmware-Notiz b24).
 */
const val TS_BEDROCK_SCHRIFTEN_AB_BUILD = 24

/**
 * Die Nummer N aus "v4.38.4-bN". Null, wenn es keine gibt: ein Entwicklungsbau
 * ohne "-bN" oder eine fremde Fassung - beides gilt als "unbekannt". Null auch
 * fuer Boulder V2, die das Zifferblatt nicht mehr hat ([tsOhneBedrock]).
 */
fun tsFirmwareBuild(laufendeFassung: String?): Int? =
    laufendeFassung?.let { ReleaseTagVersion.from(it) }?.takeUnless { it.boulderV2 }?.boulder?.takeIf { it > 0 }

/**
 * Boulder V2 hat TimeStyle Bedrock nicht mehr; das Original kommt aus dem
 * Store. Dann fehlt die Kachel wie auf einer Uhr ohne emery, statt mit dem
 * Hinweis auf eine Firmware dazustehen, die es nicht mehr bringt.
 */
fun tsOhneBedrock(laufendeFassung: String?): Boolean =
    laufendeFassung?.let { ReleaseTagVersion.from(it) }?.boulderV2 == true

/**
 * Was die Uhr mit TimeStyle Bedrock kann. UNBEKANNT GILT ALS NICHT FAEHIG:
 * ohne "-bN" ist es eine offizielle Firmware oder ein Entwicklungsbau, und
 * keiner von beiden kennt TimeStyle Bedrock sicher. Lieber eine Kachel mit
 * Hinweis als ein Settings-Knopf, dessen Wert die Uhr verwirft (bis 0.48
 * galt "unbekannt" als faehig - so lief der Emulator nie durch die Sperren).
 */
data class TsBedrockFaehigkeit(
    /** Die Firmware hat das Zifferblatt. */
    val faehig: Boolean,
    /** Die Kachel ist kompatibel (sonst "Not compatible", mit Hinweis). */
    val kachel: Boolean,
    /** Der Settings-Knopf erscheint. */
    val einstellungen: Boolean,
    /** Schriftwahl, alle 37 Sprachen, UV der Stunde (ab b24). */
    val schriften: Boolean,
)

fun tsBedrockFaehigkeit(build: Int?): TsBedrockFaehigkeit {
    val faehig = build != null && build >= TS_BEDROCK_AB_BUILD
    return TsBedrockFaehigkeit(
        faehig = faehig,
        kachel = faehig,
        einstellungen = build != null && build >= TS_BEDROCK_EINSTELLUNGEN_AB_BUILD,
        schriften = build != null && build >= TS_BEDROCK_SCHRIFTEN_AB_BUILD,
    )
}

/**
 * Ob die Uhr mit Firmware-Build [build] im Widget "UV" den Wert der Stunde
 * zeigt (ab b24) oder den des Tages (b23). UNBEKANNT WIE UEBERALL NICHT
 * FAEHIG (tsBedrockFaehigkeit): bis zur Nacharbeit galt es hier und in
 * wieDieUhr als "neueste" - die Kachel einer Uhr mit offizieller Firmware
 * zeigte dann, was diese gar nicht zeichnen kann.
 */
fun tsUvJeStunde(build: Int?): Boolean = tsBedrockFaehigkeit(build).schriften

/**
 * Setzt den gespeicherten TimeStyle-Wert EINMAL neu, wenn eine Uhr zum
 * ersten Mal mit einer Firmware verbindet, die ihn annimmt.
 *
 * Warum: hat eine aeltere Firmware den Schluessel abgewiesen, markiert die
 * Synchronisation den Eintrag trotzdem als erledigt - er kaeme nach dem
 * Update nie an. Ein neuer Zeitstempel macht ihn wieder "zu senden".
 * Der zuletzt gesehene Build steht je Seriennummer in [lies]/[schreibe];
 * die Verbindung selbst wird nur beobachtet, nie angefasst.
 */
class TsBedrockNeusetzen(
    private val lies: (serie: String) -> String?,
    private val schreibe: (serie: String, build: String) -> Unit,
    private val gespeicherterWert: suspend () -> String?,
    private val setze: (hex: String) -> Unit,
) {
    constructor(
        settings: Settings,
        gespeicherterWert: suspend () -> String?,
        setze: (hex: String) -> Unit,
    ) : this(
        lies = { settings.getStringOrNull(SCHLUESSEL + it) },
        schreibe = { serie, build -> settings.putString(SCHLUESSEL + serie, build) },
        gespeicherterWert = gespeicherterWert,
        setze = setze,
    )

    private val logger = Logger.withTag("TsBedrockNeusetzen")

    /** Eine Uhr ist (wieder) voll verbunden. Gibt zurueck, ob neu gesetzt wurde. */
    suspend fun verbunden(serie: String, laufendeFassung: String): Boolean {
        val jetzt = tsFirmwareBuild(laufendeFassung)
        // null = nie gesehen; "0" = gesehen, aber ohne Nummer (Entwicklungsbau, offizielle Firmware).
        val vorher = lies(serie)
        schreibe(serie, (jetzt ?: 0).toString())
        // "0" ZAEHLT NICHT ALS FAEHIG: offizielle Firmware ohne -bN weist tsBedrock ab und
        // markiert den Eintrag trotzdem als synchron - nach dem Wechsel auf Boulder kaeme er nie an.
        val vorherBuild = vorher?.toIntOrNull()?.takeIf { it > 0 }
        val vorherKonnte = vorherBuild != null && tsBedrockFaehigkeit(vorherBuild).einstellungen
        // Zweimal ohne Nummer hintereinander: sonst ginge der Wert bei jeder Verbindung neu raus.
        val wiederUnbekannt = vorher == "0" && jetzt == null
        if (vorherKonnte || wiederUnbekannt || !tsBedrockFaehigkeit(jetzt).einstellungen) return false
        val wert = gespeicherterWert() ?: return false
        logger.i { "Firmware $laufendeFassung accepts tsBedrock now (was ${vorher ?: "unseen"}): sending it again" }
        setze(wert)
        return true
    }

    /** Beobachtet die Uhrenliste und meldet jede neue Verbindung (Seriennummer + Fassung) einmal. */
    suspend fun beobachte(uhren: Flow<List<PebbleDevice>>) {
        uhren.map { liste -> liste.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()?.let { it.serial to it.runningFwVersion } }
            // Die Liste kommt bei jeder Akkuzahl neu - nur ein Wechsel der Uhr oder Fassung zaehlt.
            .distinctUntilChanged()
            .filterNotNull()
            .collect { (serie, fassung) -> verbunden(serie, fassung) }
    }

    companion object {
        private const val SCHLUESSEL = "ts_bedrock_build_"

        /** Der Wert aus der Uhreinstellungen-Liste, null wenn ihn nie jemand gesetzt hat. */
        fun ausListe(prefs: List<WatchPreference<*>>): String? =
            prefs.firstOrNull { it.pref == BytesWatchPref.TimeStyleBedrock }?.value as String?
    }
}

/**
 * Ob Boulder auf dieser Plattform eine Einstellungsseite fuer TimeStyle
 * Bedrock hat. Android ja; iOS zeigt nur "Only available on Android." - dort
 * darf kein Settings-Knopf erscheinen (hasSettings).
 */
expect val tsBedrockSeiteVorhanden: Boolean
