package org.yoshiz.app.prioritarr.backend.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

private val requiredEnvForParser = mapOf(
    "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
    "PRIORITARR_SONARR_API_KEY" to "k",
    "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
    "PRIORITARR_TAUTULLI_API_KEY" to "k",
    "PRIORITARR_QBIT_URL" to "http://vpn:8080",
    "PRIORITARR_SAB_URL" to "http://sab:8080",
    "PRIORITARR_SAB_API_KEY" to "k",
)

/** Parse settings from a YAML string, layered on top of defaults. */
private fun parseSettingsFromYamlString(yaml: String): Settings {
    val file = Files.createTempFile("prioritarr-parser-test-", ".yaml").toFile()
    try {
        file.writeText(yaml)
        return loadSettingsFrom(requiredEnvForParser + ("PRIORITARR_CONFIG_PATH" to file.absolutePath))
    } finally {
        file.delete()
    }
}

class SettingsParserTest {

    @Test
    fun yaml_loads_backfill_p1p2_fields() {
        val yaml = """
            intervals:
              backfill_p1_p2_max_per_sweep: 25
              backfill_p1_p2_cooldown_minutes: 45
              backfill_p1_p2_followup_episodes: 3
        """.trimIndent()
        val settings = parseSettingsFromYamlString(yaml)
        assertEquals(25, settings.intervals.backfillP1P2MaxPerSweep)
        assertEquals(45, settings.intervals.backfillP1P2CooldownMinutes)
        assertEquals(3, settings.intervals.backfillP1P2FollowupEpisodes)
    }

    @Test
    fun intervals_p1p2_fields_have_documented_defaults() {
        val intervals = Intervals()
        assertEquals(20, intervals.backfillP1P2MaxPerSweep)
        assertEquals(30, intervals.backfillP1P2CooldownMinutes)
        assertEquals(2, intervals.backfillP1P2FollowupEpisodes)
    }
}
