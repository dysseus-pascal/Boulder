package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.lang.reflect.Proxy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Datalogging-Ablage muss nach dem Umbau (Bereichsabfrage + ein Insert je
 * Paket) genau dasselbe in der Datenbank hinterlassen wie die alte Fassung
 * (je Satz eine Abfrage und ein Insert). Die alte Fassung steht hier als
 * Vergleich, Satz fuer Satz wie sie war.
 */
class HealthDaoExtTest {

    @Test
    fun mehrSchritteGewinnenGleichstandBehaeltDenBestand() = runTest {
        val dao = FakeHealthDao()
        dao.zeilen[60] = minute(60, schritte = 10, puls = 70)
        dao.zeilen[120] = minute(120, schritte = 10, puls = 70)
        dao.zeilen[180] = minute(180, schritte = 10, puls = 70)
        dao.insertHealthDataWithPriority(
            listOf(
                minute(60, schritte = 11, puls = 90),  // mehr: ersetzt
                minute(120, schritte = 10, puls = 90), // gleich: bleibt alt
                minute(180, schritte = 9, puls = 90),  // weniger: bleibt alt
                minute(240, schritte = 0, puls = 55),  // neu
            )
        )
        assertEquals(90, dao.zeilen.getValue(60).heartRate)
        assertEquals(70, dao.zeilen.getValue(120).heartRate)
        assertEquals(70, dao.zeilen.getValue(180).heartRate)
        assertEquals(55, dao.zeilen.getValue(240).heartRate)
        // Ein Paket: eine Bereichsabfrage, ein Insert.
        assertEquals(1, dao.bereichsabfragen)
        assertEquals(1, dao.inserts)
    }

    @Test
    fun nachgelieferterPulsBeiGleichenSchrittenGewinnt() = runTest {
        // Boulder: vorher blieb die Minute ohne Puls stehen, weil nur die Schritte zaehlten.
        val dao = FakeHealthDao()
        dao.zeilen[60] = minute(60, schritte = 10, puls = 0)
        dao.zeilen[120] = minute(120, schritte = 10, puls = 70)
        dao.insertHealthDataWithPriority(
            listOf(
                minute(60, schritte = 10, puls = 72),  // Puls kommt nach: ersetzt
                minute(120, schritte = 10, puls = 0),  // Puls ginge verloren: bleibt alt
            )
        )
        assertEquals(72, dao.zeilen.getValue(60).heartRate)
        assertEquals(70, dao.zeilen.getValue(120).heartRate)
    }

    @Test
    fun gleicheMinuteImSelbenPaketVergleichtSichDerReiheNach() = runTest {
        val alt = FakeHealthDao()
        val neu = FakeHealthDao()
        val paket = listOf(
            minute(60, schritte = 5, puls = 1),
            minute(60, schritte = 8, puls = 2),
            minute(60, schritte = 8, puls = 3),
            minute(60, schritte = 7, puls = 4),
        )
        alt.altHealth(paket)
        neu.insertHealthDataWithPriority(paket)
        assertEquals(alt.zeilen, neu.zeilen)
        assertEquals(2, neu.zeilen.getValue(60).heartRate)
    }

    @Test
    fun zufaelligePaketeGebenDasselbeWieDieAlteFassung() = runTest {
        val zufall = Random(4711)
        repeat(200) {
            val alt = FakeHealthDao()
            val neu = FakeHealthDao()
            // Bestand und Pakete teilen sich wenige Minuten, damit viele Kollisionen entstehen.
            val bestand = List(zufall.nextInt(0, 30)) { zufallsminute(zufall) }
            alt.zeilen.putAll(bestand.associateBy { it.timestamp })
            neu.zeilen.putAll(bestand.associateBy { it.timestamp })
            repeat(zufall.nextInt(1, 5)) {
                val paket = List(zufall.nextInt(0, 40)) { zufallsminute(zufall) }
                alt.altHealth(paket)
                neu.insertHealthDataWithPriority(paket)
            }
            assertEquals(alt.zeilen, neu.zeilen)
        }
    }

    @Test
    fun nachholschubUeberTageLiestTageweise() = runTest {
        val dao = FakeHealthDao()
        val tage = 3L
        val paket = (0 until tage * 1440).map { minute(1_000_000L + it * 60, schritte = 1, puls = 60) }
        dao.insertHealthDataWithPriority(paket)
        assertEquals(tage.toInt(), dao.bereichsabfragen)
        assertEquals(1, dao.inserts)
        assertEquals(paket.size, dao.zeilen.size)
    }

    @Test
    fun gleichzeitigePaketeMitDerselbenMinuteBehaltenDieMeistenSchritte() = runTest {
        val dao = FakeHealthDao()
        val pakete = (1..20).map { s -> listOf(minute(60, schritte = s, puls = s)) }.shuffled(Random(1))
        pakete.map { async { dao.insertHealthDataWithPriority(it) } }.awaitAll()
        assertEquals(20, dao.zeilen.getValue(60).steps)
    }

    @Test
    fun overlaysLaengerGewinntWieBisher() = runTest {
        val zufall = Random(99)
        repeat(200) {
            val alt = FakeHealthDao()
            val neu = FakeHealthDao()
            val bestand = List(zufall.nextInt(0, 10)) { zufallsoverlay(zufall) }
            bestand.forEach { o -> alt.overlays[o.startTime to o.type] = o; neu.overlays[o.startTime to o.type] = o }
            repeat(zufall.nextInt(1, 4)) {
                val paket = List(zufall.nextInt(0, 12)) { zufallsoverlay(zufall) }
                alt.altOverlay(paket)
                neu.insertOverlayDataWithDeduplication(paket)
            }
            assertEquals(alt.overlays, neu.overlays)
        }
    }

    @Test
    fun zeitbereicheDeckenJedeZeitGenauEinmal() {
        val zeiten = listOf(500L, 100L, 100L + BEREICH_SEKUNDEN - 1, 100L + BEREICH_SEKUNDEN, 900_000L)
        val bereiche = zeitbereiche(zeiten)
        assertEquals(
            listOf(100L to 100L + BEREICH_SEKUNDEN, 100L + BEREICH_SEKUNDEN to 100L + BEREICH_SEKUNDEN + 1, 900_000L to 900_001L),
            bereiche,
        )
        zeiten.forEach { t -> assertEquals(1, bereiche.count { (von, bis) -> t in von until bis }) }
        bereiche.forEach { (von, bis) -> assertTrue(bis - von <= BEREICH_SEKUNDEN) }
    }

    private fun minute(t: Long, schritte: Int, puls: Int) = HealthDataEntity(
        timestamp = t, steps = schritte, orientation = 0, intensity = 0, lightIntensity = 0,
        activeMinutes = 0, restingGramCalories = 0, activeGramCalories = 0, distanceCm = 0, heartRate = puls,
    )

    private fun zufallsminute(z: Random) = minute(z.nextLong(0, 12) * 60, schritte = z.nextInt(0, 6), puls = z.nextInt(40, 200))

    private fun zufallsoverlay(z: Random) = OverlayDataEntity(
        startTime = z.nextLong(0, 6) * 3600, duration = z.nextLong(0, 5) * 600, type = z.nextInt(1, 4),
        steps = z.nextInt(0, 1000), restingKiloCalories = 0, activeKiloCalories = 0, distanceM = 0, offsetUTC = 0,
    )
}

/** Eine Tabelle im Speicher; alles, was der Test nicht braucht, wirft. */
private class FakeHealthDao : HealthDao by unbenutzt() {
    val zeilen = sortedMapOf<Long, HealthDataEntity>()
    val overlays = mutableMapOf<Pair<Long, Int>, OverlayDataEntity>()
    var bereichsabfragen = 0
    var inserts = 0

    override suspend fun getHealthDataForRange(start: Long, end: Long): List<HealthDataEntity> {
        bereichsabfragen++
        // Wie eine echte Abfrage gibt sie den Faden ab - so laufen gleichzeitige Pakete wirklich verschraenkt.
        yield()
        return zeilen.subMap(start, end).values.toList()
    }

    override suspend fun insertHealthData(data: List<HealthDataEntity>) {
        inserts++
        data.forEach { zeilen[it.timestamp] = it }
    }

    override suspend fun getDataAtTimestamp(timestamp: Long): HealthDataEntity? = zeilen[timestamp]

    override suspend fun getOverlayEntries(start: Long, end: Long, types: List<Int>): List<OverlayDataEntity> =
        overlays.values.filter { it.startTime >= start && it.startTime < end && it.type in types }

    override suspend fun getOverlayAtStartTimeAndType(startTime: Long, type: Int): OverlayDataEntity? =
        overlays[startTime to type]

    override suspend fun insertOverlayData(data: List<OverlayDataEntity>) {
        data.forEach { overlays[it.startTime to it.type] = it }
    }

    /** Die Fassung vor dem Umbau, ohne Logzeilen. */
    suspend fun altHealth(data: List<HealthDataEntity>) {
        data.forEach { neu ->
            val alt = getDataAtTimestamp(neu.timestamp)
            if (alt == null || neu.steps > alt.steps) insertHealthData(listOf(neu))
        }
    }

    suspend fun altOverlay(data: List<OverlayDataEntity>) {
        data.forEach { neu ->
            val alt = getOverlayAtStartTimeAndType(neu.startTime, neu.type)
            if (alt == null || neu.duration > alt.duration) insertOverlayData(listOf(neu))
        }
    }
}

// Ueber einen Proxy, damit eine neue Abfrage in HealthDao diesen Test nicht bricht.
private fun unbenutzt(): HealthDao = Proxy.newProxyInstance(
    HealthDao::class.java.classLoader,
    arrayOf(HealthDao::class.java),
) { _, methode, _ -> throw UnsupportedOperationException(methode.name) } as HealthDao
