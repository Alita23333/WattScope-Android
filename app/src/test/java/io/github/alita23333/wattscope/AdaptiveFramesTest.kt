package io.github.alita23333.wattscope

import org.junit.Assert.*
import org.junit.Test

class AdaptiveFramesTest {
    private fun regular(count: Int, step: Long = 16_666_667L, start: Long = 0L) =
        (0..count).map { start + it * step }

    @Test
    fun normal60FpsDoesNotBecomeSlowBecauseOfAnOld120FpsSetting() {
        val summary = Statistics.frameStats(listOf(regular(900)))
        assertEquals(60.0, summary.frameBaseline.fps!!, .0001)
        assertEquals(0L, summary.slow)
        assertEquals(0L, summary.severe)
    }

    @Test
    fun occasionalStallsDoNotMoveTheMedianBudget() {
        val times = mutableListOf(0L)
        repeat(900) { index ->
            times += times.last() + if (index == 100 || index == 850) 100_000_000L else 16_666_667L
        }
        val summary = Statistics.frameStats(listOf(times))
        assertEquals(16.666667, summary.frameBaseline.budgetMs!!, .000001)
        assertEquals(2L, summary.slow)
        assertEquals(2L, summary.severe)
    }

    @Test
    fun fewerThan10SecondsHasUnknownCountsAndSurvivesExport() {
        val summary = Statistics.frameStats(listOf(regular(500)))
        assertNotNull(summary.avgFps)
        assertNull(summary.frameBaseline.fps)
        assertNull(summary.slow)
        assertNull(summary.severe)
        val json = Transfer.summaryJson(summary)
        assertTrue(json.has("slowFrames"))
        assertTrue(json.isNull("slowFrames"))
        assertEquals("insufficient", json.getJSONObject("frameBaseline").getString("status"))
        assertEquals(summary, Transfer.summaryFromJson(json))
    }

    @Test
    fun pauseTimeCannotCompleteCalibrationOrCreateSlowFrames() {
        val short = listOf(regular(240), regular(240, start = 3_600_000_000_000L))
        assertNull(Statistics.frameStats(short).slow)
        val complete = short + listOf(regular(240, start = 7_200_000_000_000L))
        val summary = Statistics.frameStats(complete)
        assertEquals(60.0, summary.frameBaseline.fps!!, .0001)
        assertEquals(0L, summary.slow)
        assertTrue(summary.frameBaseline.observedSeconds < 10.1)
    }

    @Test
    fun layerAndCollectorChangesDoNotEnterTheCalibrationBudget() {
        val calibration = FrameCalibration()
        regular(240).forEach { calibration.add(Frame(it, 0, "old", 0)) }
        regular(240, start = 60_000_000_000L).forEach { calibration.add(Frame(it, 0, "new", 1)) }
        assertNull(calibration.result().fps)
        calibration.boundary()
        regular(240, start = 120_000_000_000L).forEach { calibration.add(Frame(it, 0, "new", 1)) }
        assertEquals(60.0, calibration.result().fps!!, .0001)
    }

    @Test
    fun aShortSlowRangeUsesTheFullRecordingBaselineInsteadOfLearningItsOwnSlowRate() {
        val baseline = Statistics.calibrate(listOf(regular(900)))
        val range = listOf(regular(60, step = 33_333_334L))
        val summary = Statistics.frameStats(range, baseline)
        assertEquals(baseline, summary.frameBaseline)
        assertEquals(60L, summary.slow)
        assertEquals(0L, summary.severe)
        assertNull(Statistics.frameStats(range).slow)
    }

    @Test
    fun learningStopsOnceAndDoesNotNormalizeALaterSustainedDrop() {
        val normal = regular(900)
        val slowed = regular(900, step = 33_333_334L, start = normal.last()).drop(1)
        val summary = Statistics.frameStats(listOf(normal + slowed))
        assertEquals(60.0, summary.frameBaseline.fps!!, .0001)
        assertEquals(900L, summary.slow)
        assertEquals(0L, summary.severe)
    }

    @Test
    fun aSingleLongFrameCannotSupplyEnoughCalibrationSamples() {
        val summary = Statistics.frameStats(listOf(listOf(0L, 20_000_000_000L)))
        assertNull(summary.frameBaseline.fps)
        assertNull(summary.slow)
    }
}
