package org.yoshiz.app.prioritarr.backend.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

private val requiredEnv = mapOf(
    "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
    "PRIORITARR_SONARR_API_KEY" to "k",
    "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
    "PRIORITARR_TAUTULLI_API_KEY" to "k",
    "PRIORITARR_QBIT_URL" to "http://vpn:8080",
    "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
    "PRIORITARR_SAB_API_KEY" to "k",
)

/** Parse settings from a YAML string, layered on top of defaults. Mirrors SettingsParserTest's helper. */
private fun parseSettingsFromYamlString(yaml: String): Settings {
    val file = Files.createTempFile("prioritarr-sub-ladder-parser-test-", ".yaml").toFile()
    try {
        file.writeText(yaml)
        return loadSettingsFrom(requiredEnv + ("PRIORITARR_CONFIG_PATH" to file.absolutePath))
    } finally {
        file.delete()
    }
}

class SubLadderSettingsTest {

    @Test
    fun defaults_are_conservative_and_off() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
                "PRIORITARR_SONARR_API_KEY" to "k",
                "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
                "PRIORITARR_TAUTULLI_API_KEY" to "k",
                "PRIORITARR_QBIT_URL" to "http://vpn:8080",
                "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
                "PRIORITARR_SAB_API_KEY" to "k",
            ),
        )
        assertEquals(false, s.subLadderEnabled, "must ship disabled")
        assertEquals(false, s.subLadderWhisperEnabled, "whisper rung must ship disabled")
        assertEquals(2, s.subLadderWhisperMaxPriority, "P1/P2 by default")
        assertEquals(30, s.intervals.subLadderIntervalMinutes)
        assertEquals(5, s.intervals.subLadderMaxPerSweep, "start low; no Bazarr guard on this path")
        assertEquals(
            10, s.intervals.subLadderMaxSeriesPerSweep,
            "bounds the per-sweep getEpisodes fan-out regardless of how much is due",
        )
    }

    @Test
    fun env_overrides_apply() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
                "PRIORITARR_SONARR_API_KEY" to "k",
                "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
                "PRIORITARR_TAUTULLI_API_KEY" to "k",
                "PRIORITARR_QBIT_URL" to "http://vpn:8080",
                "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
                "PRIORITARR_SAB_API_KEY" to "k",
                "PRIORITARR_SUB_LADDER_ENABLED" to "true",
                "PRIORITARR_SUB_LADDER_WHISPER_ENABLED" to "true",
                "PRIORITARR_SUB_LADDER_WHISPER_MAX_PRIORITY" to "1",
            ),
        )
        assertEquals(true, s.subLadderEnabled)
        assertEquals(true, s.subLadderWhisperEnabled)
        assertEquals(1, s.subLadderWhisperMaxPriority)
    }

    // ---- override-merge + YAML coverage for the sub_ladder_* interval
    // knobs, mirroring SubExtractSettingsTest's pattern. These paths
    // (applySettingsOverride's intervals.copy(...) block and the YAML
    // parser's sub_ladder_* keys) shipped with the settings themselves
    // but had no test exercising them.

    private fun base() = Settings(
        sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
        qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
    )

    @Test
    fun override_merge_applies_sub_ladder_interval_knobs() {
        val merged = applySettingsOverride(
            base(),
            EditableSettings(
                subLadderIntervalMinutes = 45,
                subLadderMaxPerSweep = 12,
                subLadderMaxSeriesPerSweep = 25,
            ),
        )
        assertEquals(45, merged.intervals.subLadderIntervalMinutes)
        assertEquals(12, merged.intervals.subLadderMaxPerSweep)
        assertEquals(25, merged.intervals.subLadderMaxSeriesPerSweep)
    }

    @Test
    fun override_merge_null_leaves_baseline_untouched() {
        val merged = applySettingsOverride(base(), EditableSettings())
        assertEquals(30, merged.intervals.subLadderIntervalMinutes)
        assertEquals(5, merged.intervals.subLadderMaxPerSweep)
        assertEquals(10, merged.intervals.subLadderMaxSeriesPerSweep)
    }

    @Test
    fun yaml_loads_sub_ladder_interval_fields() {
        val yaml = """
            intervals:
              sub_ladder_interval_minutes: 15
              sub_ladder_max_per_sweep: 8
              sub_ladder_max_series_per_sweep: 40
        """.trimIndent()

        val settings = parseSettingsFromYamlString(yaml)

        assertEquals(15, settings.intervals.subLadderIntervalMinutes)
        assertEquals(8, settings.intervals.subLadderMaxPerSweep)
        assertEquals(40, settings.intervals.subLadderMaxSeriesPerSweep)
    }
}
