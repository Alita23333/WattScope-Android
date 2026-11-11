package io.github.alita23333.wattscope

import org.junit.Assert.*
import org.junit.Test

class BatteryPowerTest {
    private fun sample(status: String, power: Double?, ns: Long = 0) =
        Hardware(
            ns,
            true,
            status,
            mapOf(
                "battery.power" to power,
                "battery.current" to -2.0,
                "battery.voltage" to 4.0,
                "qpt.soc" to 3.0,
            ),
        )

    @Test
    fun liveChargeIsPositiveAndDischargeIsNegative() {
        for (raw in listOf(-8.0, 8.0)) {
            assertEquals(8.0, BatteryPower.display(sample("Charging", raw))!!, 0.0)
            assertEquals(-8.0, BatteryPower.display(sample("Discharging", raw))!!, 0.0)
        }
        assertNull(BatteryPower.display(sample("Charging", null)))
        assertNull(BatteryPower.display(sample("Discharging", Double.NaN)))
    }

    @Test
    fun recordingZerosChargingAndRetainsPositiveDischarge() {
        for (status in listOf("Charging", "Full", "Not charging")) for (raw in
            listOf(null, -8.0, 8.0)) assertEquals(
            0.0,
            BatteryPower.recorded(sample(status, raw))!!,
            0.0,
        )
        assertEquals(8.0, BatteryPower.recorded(sample("Discharging", -8.0))!!, 0.0)
        assertNull(BatteryPower.recorded(sample("Discharging", null)))
        assertNull(BatteryPower.recorded(sample("Unknown", 8.0)))
    }

    @Test
    fun storedProtocolKeepsOriginalInputsAndNormalizationIsIdempotent() {
        val raw = sample("Charging", -8.0)
        val stored = Hardware.parse(raw.recordLine())
        assertEquals(0.0, stored.values["battery.power"]!!, 0.0)
        assertEquals(-2.0, stored.values["battery.current"]!!, 0.0)
        assertEquals(4.0, stored.values["battery.voltage"]!!, 0.0)
        assertEquals(3.0, stored.values["qpt.soc"]!!, 0.0)
        assertTrue(stored.sources["battery.power"]!!.contains("raw=-8.0"))
        assertEquals(stored, stored.forRecording())
        assertEquals(-8.0, raw.values["battery.power"]!!, 0.0)
    }

    @Test
    fun chargeDischargeBoundaryNeverDilutesDischargeAverage() {
        val samples =
            listOf(
                sample("Charging", -100.0, 0),
                sample("Charging", -100.0, 500_000_000),
                sample("Discharging", -4.0, 1_000_000_000),
                sample("Discharging", -6.0, 1_500_000_000),
            )
        val stats = Statistics.power(samples, "battery.power", 500)
        assertEquals(5.0, stats.average!!, 1e-9)
        assertEquals(6.0, stats.peak!!, 1e-9)
        assertEquals(2.5, stats.energyJ!!, 1e-9)
        assertEquals(.5, stats.coverageSeconds, 1e-9)
        assertEquals(
            stats,
            Statistics.power(samples.map { it.forRecording() }, "battery.power", 500),
        )
    }

    @Test
    fun legacyRowsUseTheSamePolicyAsNewlyWrittenRows() {
        val raw =
            "H|100|1|Charging|{\"battery.power\":-5,\"gpu.usage\":null}|{}|{\"gpu.usage\":{\"state\":\"missing\",\"reason\":\"no window\"}}"
        val normalized = Hardware.parse(raw).forRecording()
        val stored = Hardware.parse(Hardware.parse(raw).recordLine())
        assertEquals(normalized, stored)
        assertNull(stored.values["gpu.usage"])
        assertEquals("missing", stored.issues["gpu.usage"]!!.state)
    }
}
