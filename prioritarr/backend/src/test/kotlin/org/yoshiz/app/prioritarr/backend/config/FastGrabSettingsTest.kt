package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastGrabSettingsTest {
    @Test fun defaults_are_present() {
        val i = Intervals()
        assertTrue(i.p1FastEnabled)
        assertEquals(20, i.p1FastSweepMinutes)
        assertEquals(60, i.p1FastReleaseDelayMinutes)
        assertEquals(48, i.p1FastWindowHours)
        assertEquals(20, i.p1FastCooldownMinutes)
        assertEquals(10, i.p1FastMaxPerSweep)
        assertEquals(30, i.p1StallMinutes)
    }

    @Test fun override_applies_onto_base_intervals() {
        val base = Settings(
            sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
            qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
        )
        val merged = applySettingsOverride(
            base,
            EditableSettings(p1FastEnabled = false, p1FastSweepMinutes = 5, p1StallMinutes = 45),
        )
        assertEquals(false, merged.intervals.p1FastEnabled)
        assertEquals(5, merged.intervals.p1FastSweepMinutes)
        assertEquals(45, merged.intervals.p1StallMinutes)
        assertEquals(48, merged.intervals.p1FastWindowHours)
    }
}
