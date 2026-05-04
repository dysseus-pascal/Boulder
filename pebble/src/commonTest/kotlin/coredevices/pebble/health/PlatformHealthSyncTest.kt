package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.records.SleepSessionRecord
import com.viktormykhailiv.kmp.health.records.SleepStageType
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class PlatformHealthSyncTest {

    @Test
    fun sleepContainerWithDeepSubintervals_emitsAlternatingLightAndDeep() {
        // 8h Sleep container with two DeepSleep periods nested inside (the reporter scenario).
        val stages = computeSleepStages(
            listOf(
                overlay(start = 0, duration = 28800, type = OverlayType.Sleep),
                overlay(start = 3600, duration = 1800, type = OverlayType.DeepSleep),
                overlay(start = 14400, duration = 3600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(
            listOf(
                stage(0, 3600, SleepStageType.Light),
                stage(3600, 5400, SleepStageType.Deep),
                stage(5400, 14400, SleepStageType.Light),
                stage(14400, 18000, SleepStageType.Deep),
                stage(18000, 28800, SleepStageType.Light),
            ),
            stages,
        )
    }

    @Test
    fun sleepContainerWithNoDeeps_emitsSingleLight() {
        val stages = computeSleepStages(
            listOf(overlay(start = 100, duration = 3600, type = OverlayType.Sleep))
        )
        assertEquals(listOf(stage(100, 3700, SleepStageType.Light)), stages)
    }

    @Test
    fun deepFlushWithContainerStart_noEmptyLeadingLight() {
        val stages = computeSleepStages(
            listOf(
                overlay(start = 0, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 0, duration = 600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(
            listOf(
                stage(0, 600, SleepStageType.Deep),
                stage(600, 3600, SleepStageType.Light),
            ),
            stages,
        )
    }

    @Test
    fun deepFlushWithContainerEnd_noEmptyTrailingLight() {
        val stages = computeSleepStages(
            listOf(
                overlay(start = 0, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 3000, duration = 600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(
            listOf(
                stage(0, 3000, SleepStageType.Light),
                stage(3000, 3600, SleepStageType.Deep),
            ),
            stages,
        )
    }

    @Test
    fun splitSleep_twoContainers_eachCarvesItsOwnDeep() {
        val stages = computeSleepStages(
            listOf(
                overlay(start = 0, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 1000, duration = 500, type = OverlayType.DeepSleep),
                overlay(start = 7200, duration = 3600, type = OverlayType.Sleep),
                overlay(start = 8000, duration = 600, type = OverlayType.DeepSleep),
            )
        )
        assertEquals(
            listOf(
                stage(0, 1000, SleepStageType.Light),
                stage(1000, 1500, SleepStageType.Deep),
                stage(1500, 3600, SleepStageType.Light),
                stage(7200, 8000, SleepStageType.Light),
                stage(8000, 8600, SleepStageType.Deep),
                stage(8600, 10800, SleepStageType.Light),
            ),
            stages,
        )
    }

    @Test
    fun napContainerWithDeepNap_carvesOutDeep() {
        val stages = computeSleepStages(
            listOf(
                overlay(start = 0, duration = 1800, type = OverlayType.Nap),
                overlay(start = 600, duration = 300, type = OverlayType.DeepNap),
            )
        )
        assertEquals(
            listOf(
                stage(0, 600, SleepStageType.Light),
                stage(600, 900, SleepStageType.Deep),
                stage(900, 1800, SleepStageType.Light),
            ),
            stages,
        )
    }
}

private fun overlay(start: Long, duration: Long, type: OverlayType) = OverlayDataEntity(
    startTime = start,
    duration = duration,
    type = type.value,
    steps = 0,
    restingKiloCalories = 0,
    activeKiloCalories = 0,
    distanceCm = 0,
    offsetUTC = 0,
)

private fun stage(startSec: Long, endSec: Long, type: SleepStageType) = SleepSessionRecord.Stage(
    startTime = Instant.fromEpochSeconds(startSec),
    endTime = Instant.fromEpochSeconds(endSec),
    type = type,
)
