package io.github.alita23333.wattscope

import org.junit.Assert.*
import org.junit.Test

class MonitoringStateTest {
    @Test
    fun eitherOverlayKeepsMonitoringActiveAfterPagePreviewStops() {
        for (windows in listOf(true to false, false to true, true to true)) {
            val state = LiveState(preview = true, small = windows.first, osd = windows.second)
            assertTrue(state.copy(preview = false).captureRequested)
            val stopped = state.withMonitoring(false)
            assertFalse(stopped.preview)
            assertFalse(stopped.small)
            assertFalse(stopped.osd)
            assertFalse(stopped.captureRequested)
        }
    }

    @Test
    fun monitoringToggleNeverEndsAnOngoingRecord() {
        for (status in
            listOf("armed", "preparing", "recording", "manual_paused", "auto_paused", "saving")) {
            val record =
                LiveState(recording = status, sessionId = "existing", small = true, osd = true)
            val hidden = record.withMonitoring(false)
            assertTrue(hidden.captureRequested)
            assertEquals(status, hidden.recording)
            assertEquals(record.sessionId, hidden.sessionId)
            assertFalse(hidden.small || hidden.osd || hidden.preview)
        }
    }

    @Test
    fun enablingMonitoringDoesNotOpenWindowsOrCreateARecord() {
        val enabled = LiveState().withMonitoring(true)
        assertTrue(enabled.captureRequested)
        assertFalse(enabled.small || enabled.osd)
        assertEquals("idle", enabled.recording)
        assertNull(enabled.sessionId)
        assertFalse(enabled.withMonitoring(false).captureRequested)
    }
}
