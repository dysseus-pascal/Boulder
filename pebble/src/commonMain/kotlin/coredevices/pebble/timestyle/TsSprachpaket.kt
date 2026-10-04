package coredevices.pebble.timestyle

import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Was Boulder ueber das Sprachpaket einer Uhr weiss. WARUM drei Faelle statt
 * "Paket oder null": das Paket meldet die Uhr nur, solange sie verbunden ist.
 * Bis zur Nacharbeit hiess "getrennt" dasselbe wie "kein Paket", und die
 * Seite zeigte "language pack" an allen acht Sprachen - auch wenn das Paket
 * auf der Uhr lag.
 */
sealed interface TsPaketWissen {
    /** Die Uhr wurde mit dieser Fassung von Boulder noch nie verbunden gesehen. */
    data object Unbekannt : TsPaketWissen

    /** [paket] wie die Uhr es zuletzt gemeldet hat ("ru_RU"), null = kein Paket. */
    data class Bekannt(val paket: String?) : TsPaketWissen
}

/**
 * Ob neben Sprache [id] "language pack" steht: NUR WENN NOETIG, also nur wenn
 * bekannt ist, dass das passende Paket fehlt. Unbekannt heisst nicht "fehlt".
 */
fun tsSprachpaketHinweis(id: Int, wissen: TsPaketWissen): Boolean = when (wissen) {
    TsPaketWissen.Unbekannt -> false
    is TsPaketWissen.Bekannt -> tsBrauchtSprachpaket(id, wissen.paket)
}

/** Seriennummer und Sprachpaket der verbundenen Uhr; null, wenn keine verbunden ist. */
fun tsVerbundenesPaket(uhren: List<PebbleDevice>): Pair<String, String?>? =
    uhren.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()
        ?.let { it.serial to it.installedLanguagePack?.isoLocal }

/**
 * Merkt sich je Seriennummer das zuletzt gemeldete Sprachpaket, damit die
 * Einstellungsseite es auch bei getrennter Uhr kennt. Die Verbindung wird nur
 * beobachtet, nie angefasst.
 */
class TsSprachpaketMerker(
    private val lies: (serie: String) -> String?,
    private val schreibe: (serie: String, paket: String) -> Unit,
) {
    constructor(settings: Settings) : this(
        lies = { settings.getStringOrNull(SCHLUESSEL + it) },
        schreibe = { serie, paket -> settings.putString(SCHLUESSEL + serie, paket) },
    )

    /** Die Uhr [serie] meldet [paket] (null = keins). */
    fun merke(serie: String, paket: String?) = schreibe(serie, paket ?: KEIN_PAKET)

    /**
     * Was ueber die Uhr [serie] bekannt ist: verbunden das, was sie JETZT meldet
     * ([paketJetzt]), getrennt das zuletzt gemerkte.
     */
    fun wissen(serie: String?, verbunden: Boolean, paketJetzt: String?): TsPaketWissen = when {
        verbunden -> TsPaketWissen.Bekannt(paketJetzt)
        serie == null -> TsPaketWissen.Unbekannt
        else -> lies(serie)?.let { TsPaketWissen.Bekannt(it.ifEmpty { null }) } ?: TsPaketWissen.Unbekannt
    }

    /** Merkt bei jeder Verbindung (und jedem Wechsel des Pakets) die Meldung der Uhr. */
    suspend fun beobachte(stand: Flow<Pair<String, String?>?>) {
        stand.distinctUntilChanged().filterNotNull().collect { (serie, paket) -> merke(serie, paket) }
    }

    /** Fuer Boulders Uhrenliste. */
    suspend fun beobachteUhren(uhren: Flow<List<PebbleDevice>>) = beobachte(uhren.map(::tsVerbundenesPaket))

    companion object {
        private const val SCHLUESSEL = "ts_bedrock_sprachpaket_"

        /** "Kein Paket" im Speicher - eine echte Kennung ist nie leer (installedLanguagePack). */
        private const val KEIN_PAKET = ""
    }
}
