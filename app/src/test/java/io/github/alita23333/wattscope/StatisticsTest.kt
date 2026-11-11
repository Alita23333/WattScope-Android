package io.github.alita23333.wattscope

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    @Test
    fun unspecifiedSystemJankIsMissingInsteadOfAFalseZero() {
        assertNull(Statistics.systemJank(emptyMap()))
        assertNull(Statistics.systemJank(mapOf(0L to 30L)))
        assertEquals(0L, Statistics.systemJank(mapOf(1L to 30L)))
        assertEquals(2L, Statistics.systemJank(mapOf(0L to 10L, 1L to 30L, 80L to 2L)))
    }

    @Test
    fun completeDurationUsesExplicitEndAndInterruptedUsesLatestCommittedData() {
        assertEquals(10_000L, Statistics.recordEnd(10_000, 9_500, false))
        assertEquals(10_100L, Statistics.recordEnd(9_500, 10_100, true))
        assertEquals(9_500L, Statistics.recordEnd(0, 9_500, true))
        assertEquals(10_000L, Statistics.recordEnd(10_000, 10_100, false))
    }

    @Test
    fun overviewKeepsPeaksAndEveryMissingRunWhileZoomUsesOriginalPoints() {
        val raw =
            (0 until 10000).map { i ->
                i.toDouble() to
                    when (i) {
                        1200 -> 999.0
                        1202,
                        1204,
                        1206 -> null
                        else -> 1.0
                    }
            }
        val overview = PlotData.decimate(raw, 600)
        assertTrue(overview.contains(1200.0 to 999.0))
        for (i in listOf(1202, 1204, 1206)) assertTrue(overview.contains(i.toDouble() to null))
        assertEquals(10000, raw.size)
        val series = Series("qpt.soc", raw)
        assertEquals(raw.subList(1200, 1208), series.visible(1200.0, 1207.0))
        assertNull(series.nearest(1203.9)?.second)
        assertNull(series.nearest(11000.0))
    }

    @Test
    fun realSamplingGapAndPauseRemainExplicitAfterReduction() {
        val points = listOf(0.0 to 1.0, .5 to 2.0, 5.0 to 3.0, 5.5 to 4.0)
        val result = PlotData.markGaps(points, 1.25, listOf(5.25))
        assertEquals(listOf(2.75 to null, 5.25 to null), result.filter { it.second == null })
        assertEquals(4, result.count { it.second != null })
    }

    @Test
    fun hardwareProtocolKeepsIndependentFailureAndUnsupportedReasons() {
        val h =
            Hardware.parse(
                "H|100|1|Discharging|{\"qpt.cpu-m\":0,\"gpu.freq\":null,\"ddr.freq\":null}|{}|{\"gpu.freq\":{\"state\":\"read_failed\",\"reason\":\"Permission denied\"},\"ddr.freq\":{\"state\":\"unsupported\",\"reason\":\"No clock\"}}"
            )
        assertEquals(0.0, h.values["qpt.cpu-m"]!!, 0.0)
        assertEquals("read_failed", h.issues["gpu.freq"]!!.state)
        assertEquals("unsupported", h.issues["ddr.freq"]!!.state)
        val legacy = Hardware.parse("H|100|1|Full|{\"qpt.soc\":1}|{}")
        assertTrue(legacy.issues.isEmpty())
    }

    @Test
    fun cpuTotalDoesNotDoubleCountParentOrGuessDebugRails() {
        val rails =
            listOf(
                Metric("qpt.cpu-m", "cpu-m", "W", parent = "qpt.soc"),
                Metric("qpt.cpu-l", "cpu-l", "W", parent = "qpt.soc"),
                Metric("qpt.debug-2", "debug-2", "W", parent = "qpt.soc"),
            )
        val values =
            mapOf("qpt.cpu-m" to .2, "qpt.cpu-l" to .1, "qpt.debug-2" to 3.0, "qpt.cpu" to .4)
        assertEquals(.3, Metrics.cpuPower(rails, values)!!, 1e-8)
        assertEquals(.4, Metrics.cpuPower(rails + Metric("qpt.cpu", "cpu", "W"), values)!!, 1e-8)
        assertNull(Metrics.cpuPower(rails, values - mapOf("qpt.cpu-l" to .1).keys))
    }

    @Test
    fun cachedSummaryRoundTripPreservesZeroMissingAndCounterStatistics() {
        val s =
            Summary(
                seconds = 3.5,
                frameCount = 210,
                avgFps = 60.0,
                low1 = 0.0,
                low5 = 55.0,
                p95 = 18.0,
                p99 = 20.0,
                slow = 2,
                severe = 1,
                frameBaseline = FrameBaseline(16.6667, 10.01, 600),
                systemJank = 2,
                systemJankTypes = mapOf(1L to 30L, 64L to 1L, 80L to 1L),
                powers =
                    mapOf(
                        "qpt.soc" to PowerStats(.3, .5, 1.0, 3.3),
                        "battery.power" to PowerStats(null, null, null, 0.0),
                    ),
            )
        assertEquals(s, Transfer.summaryFromJson(Transfer.summaryJson(s)))
        assertEquals("SF CPU 超期 + 应用超期", Metrics.jankLabel(80L))
    }

    @Test
    fun lowUsesMeanOfSlowFrameIntervals() {
        val times = mutableListOf(0L)
        repeat(99) { times += times.last() + 10_000_000 }
        times += times.last() + 100_000_000
        val s = Statistics.frameStats(listOf(times), FrameBaseline(10.0, 10.0, 1000))
        assertEquals(10.0, s.low1!!, 1e-8)
        assertEquals(1000.0 / 28.0, s.low5!!, 1e-8)
        assertEquals(100.0 / 1.09, s.avgFps!!, 1e-8)
        assertEquals(1L, s.slow)
        assertEquals(1L, s.severe)
    }

    @Test
    fun pauseDoesNotCreateAnArtificialLongFrame() {
        val s =
            Statistics.frameStats(
                listOf(
                    listOf(0, 10_000_000, 20_000_000),
                    listOf(9_000_000_000, 9_010_000_000, 9_020_000_000),
                ),
                FrameBaseline(10.0, 10.0, 1000),
            )
        assertEquals(100.0, s.avgFps!!, 1e-8)
        assertEquals(0L, s.slow)
    }

    @Test
    fun duplicateAndOutOfOrderPresentsAreHandled() {
        val s = Statistics.frameStats(listOf(listOf(20_000_000, 0, 10_000_000, 10_000_000)))
        assertEquals(3L, s.frameCount)
        assertEquals(100.0, s.avgFps!!, 1e-8)
    }

    private fun sample(
        t: Long,
        p: Double?,
        energy: Double? = null,
        charging: String = "Discharging",
    ) =
        Hardware(
            t,
            true,
            charging,
            mapOf("qpt.soc" to p, "qpt.soc.energy" to energy, "battery.power" to p),
        )

    @Test
    fun qptEnergyIsAuthoritativeForWindowAverage() {
        val p =
            Statistics.power(
                listOf(sample(0, 100.0, 10.0), sample(500_000_000, 100.0, 11.0)),
                "qpt.soc",
                500,
            )
        assertEquals(1.0, p.energyJ!!, 1e-8)
        assertEquals(2.0, p.average!!, 1e-8)
    }

    @Test
    fun counterResetAndMissingIntervalsAreNotZeroFilled() {
        val p =
            Statistics.power(
                listOf(
                    sample(0, 5.0, 10.0),
                    sample(500_000_000, 5.0, 1.0),
                    sample(1_000_000_000, null, null),
                    sample(1_500_000_000, 5.0, 3.0),
                ),
                "qpt.soc",
                500,
            )
        assertNull(p.average)
        assertEquals(0.0, p.coverageSeconds, 0.0)
    }

    @Test
    fun missingInstantaneousPowerDoesNotInvalidateValidEnergyCounters() {
        val p =
            Statistics.power(
                listOf(sample(0, null, 10.0), sample(500_000_000, null, 11.0)),
                "qpt.soc",
                500,
            )
        assertEquals(2.0, p.average!!, 1e-8)
        assertEquals(.5, p.coverageSeconds, 1e-8)
        assertNull(p.peak)
    }

    @Test
    fun longGapIsExcludedFromEnergyIntegration() {
        val p =
            Statistics.power(
                listOf(sample(0, 2.0), sample(500_000_000, 2.0), sample(8_000_000_000, 2.0)),
                "battery.power",
                500,
            )
        assertEquals(1.0, p.energyJ!!, 1e-8)
        assertEquals(.5, p.coverageSeconds, 1e-8)
    }

    @Test
    fun chargingNeverEntersDischargeDevicePowerAverage() {
        val p =
            Statistics.power(
                listOf(
                    sample(0, -5.0, charging = "Charging"),
                    sample(500_000_000, -5.0, charging = "Charging"),
                    sample(1_000_000_000, 3.0),
                    sample(1_500_000_000, 3.0),
                ),
                "battery.power",
                500,
            )
        assertEquals(3.0, p.average!!, 1e-8)
        assertEquals(.5, p.coverageSeconds, 1e-8)
    }

    @Test
    fun chargingPeakDoesNotEnterDischargeSummary() {
        val samples =
            listOf(
                sample(0, 100.0, charging = "Full"),
                sample(500_000_000, 2.0),
                sample(1_000_000_000, 3.0),
            )
        val p = Statistics.power(samples, "battery.power", 500)
        assertEquals(3.0, p.peak!!, 0.0)
        assertEquals(2.5, p.average!!, 1e-8)
        assertNull(Statistics.power(samples.take(1), "battery.power", 500).peak)
    }

    @Test
    fun validZeroPowerStaysValid() {
        val p =
            Statistics.power(
                listOf(sample(0, 0.0, 5.0), sample(500_000_000, 0.0, 5.0)),
                "qpt.soc",
                500,
            )
        assertEquals(0.0, p.average!!, 0.0)
        assertEquals(.5, p.coverageSeconds, 0.0)
    }

    @Test
    fun blockRoundTripAndChecksumFailure() {
        val f = Files.createTempFile("wsn", ".bin").toFile()
        try {
            BlockWriter(f).use {
                it.append("B|1")
                it.append("F|10|1|layer")
            }
            val lines = ArrayList<String>()
            assertTrue(Blocks.read(f) { lines += it })
            assertEquals(listOf("B|1", "F|10|1|layer"), lines)
            val bytes = f.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            f.writeBytes(bytes)
            assertFalse(Blocks.read(f) {})
        } finally {
            f.delete()
        }
    }

    @Test
    fun truncatedPayloadKeepsEarlierCommittedBlock() {
        val f = Files.createTempFile("wsn", ".bin").toFile()
        try {
            BlockWriter(f).use {
                it.append("first")
                it.flush()
                it.append("second")
            }
            val bytes = f.readBytes()
            f.writeBytes(bytes.copyOf(bytes.size - 2))
            val lines = ArrayList<String>()
            assertFalse(Blocks.read(f) { lines += it })
            assertEquals(listOf("first"), lines)
        } finally {
            f.delete()
        }
    }
}
