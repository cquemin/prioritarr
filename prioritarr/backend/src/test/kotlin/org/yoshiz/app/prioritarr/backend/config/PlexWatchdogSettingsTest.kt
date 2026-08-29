package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class PlexWatchdogSettingsTest {
    private fun base() = Settings(
        sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
        qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
    )

    @Test fun defaults_are_safe_and_disabled() {
        val s = base()
        assertEquals(false, s.plexWatchdogEnabled)
        assertEquals("plex", s.plexContainerName)
        assertEquals(5, s.intervals.plexWatchdogIntervalMinutes)
        assertEquals(10, s.intervals.plexWatchdogGraceMinutes)
        assertEquals(5, s.intervals.plexWatchdogAnalyzeWaitMinutes)
        assertEquals(120, s.intervals.plexWatchdogCooldownMinutes)
        assertEquals(20, s.intervals.plexWatchdogRecentItems)
    }

    @Test fun override_applies_enable_and_timers() {
        val merged = applySettingsOverride(
            base(),
            EditableSettings(plexWatchdogEnabled = true, plexWatchdogGraceMinutes = 15, plexWatchdogRecentItems = 40),
        )
        assertEquals(true, merged.plexWatchdogEnabled)
        assertEquals(15, merged.intervals.plexWatchdogGraceMinutes)
        assertEquals(40, merged.intervals.plexWatchdogRecentItems)
        assertEquals(5, merged.intervals.plexWatchdogAnalyzeWaitMinutes)
    }
}
