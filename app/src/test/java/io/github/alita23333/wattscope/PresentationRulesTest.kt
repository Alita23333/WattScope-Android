package io.github.alita23333.wattscope

import org.junit.Assert.*
import org.junit.Test

class PresentationRulesTest {
    @Test
    fun timeAxisUsesUnpaddedHourMinuteSecondLabels() {
        assertEquals("10m1s", elapsedTime(601.0))
        assertEquals("1h1m1s", elapsedTime(3661.0))
        assertEquals("47s", elapsedTime(47.0))
        assertEquals("1m0s", elapsedTime(60.0))
        assertEquals("1h0m0s", elapsedTime(3600.0))
        assertEquals("1m0.00s", elapsedTime(59.999, 2))
        assertEquals("10m1.25s", elapsedTime(601.25, 2))
        assertEquals("—", elapsedTime(Double.NaN))
    }

    @Test
    fun clustersFollowSharedPolicySourcesIncludingNonContiguousCores() {
        val metrics =
            listOf(
                Metric("cpu.cluster4.freq", "policy", "MHz", "/policy4/scaling_cur_freq"),
                Metric("cpu.cluster0.freq", "policy", "MHz", "/policy0/scaling_cur_freq"),
                Metric("cpu.0.freq", "core", "MHz", "/policy0/scaling_cur_freq"),
                Metric("cpu.2.freq", "core", "MHz", "/policy0/scaling_cur_freq"),
                Metric("cpu.4.freq", "core", "MHz", "/policy4/scaling_cur_freq"),
                Metric("cpu.5.freq", "core", "MHz", "/policy4/scaling_cur_freq"),
            )
        val clusters = CpuTopology.discover(metrics)
        assertEquals(listOf(0, 4), clusters.map { it.policy })
        assertEquals(listOf(0, 2), clusters[0].cores)
        assertEquals(listOf(4, 5), clusters[1].cores)
        assertEquals("cpu.cluster4.usage", clusters[1].usageId)
        assertEquals("CPU 0, 2", clusters[0].label)
    }

    @Test
    fun unknownSourcesNeverInventClusterMembership() {
        assertTrue(CpuTopology.discover(listOf(Metric("cpu.0.freq", "core", "MHz"))).isEmpty())
        val cluster =
            CpuTopology.discover(
                    listOf(
                        Metric("cpu.cluster0.freq", "policy", "MHz"),
                        Metric("cpu.0.freq", "core", "MHz"),
                    )
                )
                .single()
        assertTrue(cluster.cores.isEmpty())
    }
}
