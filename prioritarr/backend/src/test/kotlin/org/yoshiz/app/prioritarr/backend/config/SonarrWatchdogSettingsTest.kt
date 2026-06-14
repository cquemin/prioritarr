package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrWatchdogSettingsTest {
    @Test fun defaults_are_safe_and_disabled() {
        val s = Settings(
            sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
            qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
        )
        assertEquals(false, s.sonarrWatchdogEnabled)
        assertEquals("sonarr", s.sonarrContainerName)
        assertEquals(null, s.dockerProxyUrl)
        assertEquals(5, s.intervals.sonarrWatchdogIntervalMinutes)
        assertEquals(30, s.intervals.sonarrWatchdogStallMinutes)
        assertEquals(10, s.intervals.sonarrWatchdogRestartGraceMinutes)
        assertEquals(120, s.intervals.sonarrWatchdogCooldownMinutes)
    }

    @Test fun override_applies_enable_and_threshold() {
        val base = Settings(
            sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
            qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
        )
        val merged = applySettingsOverride(
            base,
            EditableSettings(sonarrWatchdogEnabled = true, sonarrWatchdogStallMinutes = 45),
        )
        assertEquals(true, merged.sonarrWatchdogEnabled)
        assertEquals(45, merged.intervals.sonarrWatchdogStallMinutes)
    }
}
