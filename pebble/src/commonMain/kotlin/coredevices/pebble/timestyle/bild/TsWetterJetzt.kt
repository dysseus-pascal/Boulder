package coredevices.pebble.timestyle.bild

import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/*
 * DAS WETTER, WIE DIE UHR ES ZEIGT - aus dem Satz, den Boulder ihr geschickt
 * hat (Weather.wetterDerUhr). Nachbau von Wert zu Wert aus der Firmware:
 * wetter_quelle.c (welche Felder), weather.c (Weather_refresh: frisch? Tag
 * oder Nacht? UV jetzt) und wetter_symbol.c (Symbol, Sonnenstand, Alter,
 * UV-Stunde). WARUM so genau: die Vorschau soll dasselbe Bild zeigen wie die
 * Uhr - schon ein anderes Symbol eine Minute vor Sonnenuntergang waere ein
 * Unterschied. Die Firmware-Tests (test_timestyle, wechsel_auf_die_minute)
 * und TsWetterJetztTest pruefen dieselben Zahlen.
 */

/** Wie alt die Daten hoechstens sein duerfen (WETTER_HOECHSTALTER_S). */
internal const val TS_WETTER_HOECHSTALTER_S = 3 * 60 * 60

/** Die Firmware-Werte fuer "unbekannt" im Satz v4 (weather_db.h). */
private const val UNBEKANNT_TEMP = Short.MAX_VALUE.toInt() // WETTER_UNBEKANNT
private const val UNBEKANNT_ORT = Short.MIN_VALUE.toInt() // INT16_MIN
private const val UV_STUNDE_UNBEKANNT = 255

/** WeatherType der Firmware (weather_types.h) - dieselben Nummern wie Boulders WeatherType. */
private const val SONNE = 7
private const val TEILS_BEWOELKT = 0
private const val BEWOELKT = 1
private const val LEICHTER_SCHNEE = 2
private const val LEICHTER_REGEN = 3
private const val STARKER_REGEN = 4
private const val STARKER_SCHNEE = 5
private const val REGEN_UND_SCHNEE = 8

/** wetter_symbol: die Wetterart der Firmware als TimeStyle-Symbol. */
internal fun tsWetterSymbol(art: Int, nacht: Boolean): TsWetterSymbol = when (art) {
    SONNE -> if (nacht) TsWetterSymbol.KLAR_NACHT else TsWetterSymbol.KLAR_TAG
    TEILS_BEWOELKT -> if (nacht) TsWetterSymbol.TEILS_BEWOELKT_NACHT else TsWetterSymbol.TEILS_BEWOELKT
    BEWOELKT -> TsWetterSymbol.BEWOELKT
    LEICHTER_REGEN -> TsWetterSymbol.REGEN
    STARKER_REGEN -> TsWetterSymbol.STARKREGEN
    LEICHTER_SCHNEE -> TsWetterSymbol.SCHNEE
    STARKER_SCHNEE -> TsWetterSymbol.STARKSCHNEE
    REGEN_UND_SCHNEE -> TsWetterSymbol.REGEN_UND_SCHNEE
    else -> TsWetterSymbol.ALLGEMEIN
}

// Sinus von 0,833 Grad (Brechung und Sonnenradius) in TRIG_MAX_RATIO.
private const val SIN_HORIZONT = 953

/** Hundertstelgrad in den Winkel der Ganzzahl-Trigonometrie (prv_winkel). */
private fun winkel(hundertstelGrad: Int): Int {
    var w = ((hundertstelGrad.toLong() * TRIG_MAX_ANGLE) / 36000).toInt()
    w %= TRIG_MAX_ANGLE
    return if (w < 0) w + TRIG_MAX_ANGLE else w
}

/**
 * wetter_ist_nacht: steht die Sonne fuer diesen Ort (Hundertstelgrad,
 * INT16_MIN = unbekannt -> Tag) am [tagImJahr] (1..366, UTC) zur
 * [minuteUtc] unter dem Horizont? Dieselben Ganzzahlschritte wie die Firmware.
 */
internal fun tsIstNacht(breiteE2: Int, laengeE2: Int, tagImJahr: Int, minuteUtc: Int): Boolean {
    if (breiteE2 == UNBEKANNT_ORT || laengeE2 == UNBEKANNT_ORT) return false
    val r = TRIG_MAX_RATIO.toLong()
    val b = (36000 * (tagImJahr - 81)) / 365
    val sinB = sinTabelle(winkel(b)).toLong()
    val cosB = cosTabelle(winkel(b)).toLong()
    val sin2b = sinTabelle(winkel(2 * b)).toLong()

    val dekl = ((2345L * sinB) / r).toInt()
    val sinDekl = sinTabelle(winkel(dekl)).toLong()
    val cosDekl = cosTabelle(winkel(dekl)).toLong()
    val sinBr = sinTabelle(winkel(breiteE2)).toLong()
    val cosBr = cosTabelle(winkel(breiteE2)).toLong()

    val nenner = cosBr * cosDekl
    val zaehler = -SIN_HORIZONT.toLong() * r - sinBr * sinDekl
    if (nenner <= 0) return zaehler >= 0
    val cosH = zaehler * 1000000 / nenner
    if (cosH >= 1000000) return true
    if (cosH <= -1000000) return false

    var unten = 0
    var oben = TRIG_MAX_ANGLE / 2
    while (oben - unten > 1) {
        val mitte = (unten + oben) / 2
        if (cosTabelle(mitte).toLong() * 1000000 / r > cosH) unten = mitte else oben = mitte
    }
    val halbtag = unten.toLong() * 1440000 / TRIG_MAX_ANGLE

    val zeitgleichung = (9870L * sin2b - 7530L * cosB - 1500L * sinB) / r
    val mittag = 720000L - 40L * laengeE2 - zeitgleichung

    var abstand = (minuteUtc.toLong() * 1000 - mittag) % 1440000
    if (abstand < -720000) abstand += 1440000 else if (abstand >= 720000) abstand -= 1440000
    if (abstand < 0) abstand = -abstand
    return abstand >= halbtag
}

/** wetter_frisch: vorhanden, hoechstens 3 h alt, hoechstens 1 h in der Zukunft. */
internal fun tsWetterFrisch(standUtc: Long, jetztUtc: Long): Boolean {
    if (standUtc <= 0) return false
    val alter = jetztUtc - standUtc
    return alter <= TS_WETTER_HOECHSTALTER_S && alter >= -(60 * 60)
}

/** Tage seit 1970 in Ortszeit, abgerundet (prv_tag). */
private fun tag(lokalS: Long): Long = if (lokalS >= 0) lokalS / 86400 else -((-lokalS + 86399) / 86400)

/**
 * wetter_uv_jetzt: der UV-Index der Stunde, die jetzt am naechsten liegt, in
 * der Zeit des Ortes ([ortVersatzMin], [UNBEKANNT_ORT] -> [uhrVersatzS]), nur
 * am Kalendertag des Datenstands; sonst/ohne Stundenwerte der Tageswert.
 * -1: unbekannt.
 */
internal fun tsUvJetzt(
    stundenX10: IntArray?,
    tagX10: Int,
    ortVersatzMin: Int,
    uhrVersatzS: Long,
    standUtc: Long,
    jetztUtc: Long,
): Int {
    val versatz = if (ortVersatzMin != UNBEKANNT_ORT) ortVersatzMin.toLong() * 60 else uhrVersatzS
    if (stundenX10 != null) {
        val jetzt = jetztUtc + versatz
        if (tag(jetzt) != tag(standUtc + versatz)) return -1
        val sekundeDesTages = jetzt - tag(jetzt) * 86400
        val stunde = ((sekundeDesTages + 1800) / 3600).toInt().coerceAtMost(23)
        if (stundenX10[stunde] != UV_STUNDE_UNBEKANNT) return (stundenX10[stunde] + 5) / 10
    }
    return if (tagX10 >= 0) tagX10 / 10 else -1
}

/** Wie Boulder einen Ort in den Satz schreibt (WeatherAppEntity.toCoordE2). */
private fun Double?.alsE2(): Int =
    this?.let { (it * 100).roundToInt().coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()) } ?: UNBEKANNT_ORT

/**
 * Das Wetter der Uhr aus dem Satz von Ort 0 zum Zeitpunkt [jetzt]; [uhrVersatzS]
 * = tm_gmtoff der Uhr (Boulder stellt sie auf die Zone des Telefons). Null
 * heisst wie auf der Uhr: kein (frisches) Wetter - "..." ohne Symbol.
 * [uvJeStunde]: die Firmware liest den UV-Wert der Stunde (ab b24); b23 zeigte
 * den Tageswert (today_uv_index_x10 / 10) - dann hier ebenso.
 */
fun tsWetterAusSatz(satz: WeatherAppEntry?, jetzt: Instant, uhrVersatzS: Long, uvJeStunde: Boolean = true): TsWetter? {
    if (satz == null) return null
    // weather_service.c: ein Satz ohne Ortsnamen oder ohne Zeit gibt keine Vorhersage.
    if (satz.locationName.isEmpty() || satz.lastUpdateTimeUtcSecs == 0L) return null
    val jetztUtc = jetzt.epochSeconds
    if (!tsWetterFrisch(satz.lastUpdateTimeUtcSecs, jetztUtc)) return null
    val temperatur = satz.currentTemp.toInt()
    if (temperatur == UNBEKANNT_TEMP) return null

    val art = satz.currentWeatherType.toInt() and 0xff
    val artHeute = satz.dailyForecast?.firstOrNull()?.weatherType?.code?.toInt()?.and(0xff) ?: art
    // Tag oder Nacht in UTC wie weather.c (gmtime: tm_yday + 1, Minute des Tages).
    val utc = jetzt.toLocalDateTime(TimeZone.UTC)
    val nacht = tsIstNacht(satz.latitude.alsE2(), satz.longitude.alsE2(), utc.dayOfYear, utc.hour * 60 + utc.minute)

    val hoch = satz.todayHighTemp.toInt().takeIf { it != UNBEKANNT_TEMP }
    val tief = satz.todayLowTemp.toInt().takeIf { it != UNBEKANNT_TEMP }
    val stunden = satz.todayHourly?.let { h ->
        IntArray(24) { i -> h.getOrNull(i)?.uvIndexX10?.toInt()?.takeIf { it in 0..254 } ?: UV_STUNDE_UNBEKANNT }
    } ?: IntArray(24) { UV_STUNDE_UNBEKANNT }
    val uv = tsUvJetzt(
        stundenX10 = stunden.takeIf { uvJeStunde },
        tagX10 = satz.todayUvIndexX10?.toInt()?.takeIf { it >= 0 } ?: -1,
        ortVersatzMin = satz.locationUtcOffsetMin?.toShort()?.toInt() ?: UNBEKANNT_ORT,
        uhrVersatzS = uhrVersatzS,
        standUtc = satz.lastUpdateTimeUtcSecs,
        jetztUtc = jetztUtc,
    )
    return TsWetter(
        temperatur = temperatur,
        // Die Uhr zeigt Hoch und Tief nur zusammen (weather.c).
        hoch = if (hoch != null && tief != null) hoch else null,
        tief = if (hoch != null && tief != null) tief else null,
        uv = uv.takeIf { it >= 0 },
        symbol = tsWetterSymbol(art, nacht),
        // Das Symbol des Tages ist immer das Tagsymbol (weather.c).
        symbolHeute = tsWetterSymbol(artHeute, false),
    )
}
