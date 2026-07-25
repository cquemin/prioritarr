package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SubExtractSettingsTest {
    private fun base() = Settings(
        sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
        qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
    )

    @Test fun defaults_are_safe_and_disabled() {
        val s = base()
        assertEquals(false, s.subExtractEnabled)
        assertEquals(listOf("/storage/media/video/anime"), s.subExtractPaths)
        assertEquals(listOf("en", "fr"), s.subExtractLangs)
        assertEquals(25, s.subExtractMaxPerRun)
        assertEquals(30, s.intervals.subExtractIntervalMinutes)
    }

    @Test fun override_toggles_enable_and_knobs() {
        val merged = applySettingsOverride(
            base(),
            EditableSettings(
                subExtractEnabled = true,
                subExtractIntervalMinutes = 45,
                subExtractPaths = listOf("/storage/media/video/anime", "/storage/media/video/series"),
                subExtractLangs = listOf("en"),
                subExtractMaxPerRun = 10,
            ),
        )
        assertEquals(true, merged.subExtractEnabled)
        assertEquals(45, merged.intervals.subExtractIntervalMinutes)
        assertEquals(2, merged.subExtractPaths.size)
        assertEquals(listOf("en"), merged.subExtractLangs)
        assertEquals(10, merged.subExtractMaxPerRun)
    }

    @Test fun env_parses_enabled_and_comma_lists() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "x",
                "PRIORITARR_SONARR_API_KEY" to "x",
                "PRIORITARR_TAUTULLI_URL" to "x",
                "PRIORITARR_TAUTULLI_API_KEY" to "x",
                "PRIORITARR_QBIT_URL" to "x",
                "PRIORITARR_SAB_URL" to "x",
                "PRIORITARR_SAB_API_KEY" to "x",
                "PRIORITARR_SUB_EXTRACT_ENABLED" to "true",
                "PRIORITARR_SUB_EXTRACT_PATHS" to "/a, /b ,/c",
                "PRIORITARR_SUB_EXTRACT_LANGS" to "EN, JA",
                "PRIORITARR_SUB_EXTRACT_MAX_PER_RUN" to "7",
            ),
        )
        assertEquals(true, s.subExtractEnabled)
        assertEquals(listOf("/a", "/b", "/c"), s.subExtractPaths)
        assertEquals(listOf("en", "ja"), s.subExtractLangs)
        assertEquals(7, s.subExtractMaxPerRun)
    }
}
