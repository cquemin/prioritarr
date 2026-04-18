package org.cquemin.prioritarr.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {

    private val requiredEnv = mapOf(
        "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
        "PRIORITARR_SONARR_API_KEY" to "k",
        "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
        "PRIORITARR_TAUTULLI_API_KEY" to "k",
        "PRIORITARR_QBIT_URL" to "http://vpn:8080",
        "PRIORITARR_SAB_URL" to "http://sab:8080",
        "PRIORITARR_SAB_API_KEY" to "k",
    )

    @Test
    fun `defaults applied when only required env set`() {
        val s = loadSettingsFrom(requiredEnv)
        assertEquals("INFO", s.logLevel)
        assertTrue(s.dryRun, "dryRun defaults to true")
        assertFalse(s.testMode, "testMode defaults to false")
        assertEquals(15, s.intervals.reconcileMinutes)
        assertEquals(0.90, s.priorityThresholds.p1WatchPctMin)
    }

    @Test
    fun `test mode truthy values recognised`() {
        for (value in listOf("true", "1", "yes", "TRUE", "Yes")) {
            val s = loadSettingsFrom(requiredEnv + ("PRIORITARR_TEST_MODE" to value))
            assertTrue(s.testMode, "TEST_MODE=$value should be true")
        }
    }

    @Test
    fun `test mode falsy values stay false`() {
        for (value in listOf("false", "0", "no", "")) {
            val s = loadSettingsFrom(requiredEnv + ("PRIORITARR_TEST_MODE" to value))
            assertFalse(s.testMode, "TEST_MODE=$value should be false")
        }
    }

    @Test
    fun `dry run off only when explicitly falsy`() {
        for (value in listOf("false", "0", "no")) {
            val s = loadSettingsFrom(requiredEnv + ("PRIORITARR_DRY_RUN" to value))
            assertFalse(s.dryRun, "DRY_RUN=$value should be false")
        }
        assertTrue(loadSettingsFrom(requiredEnv + ("PRIORITARR_DRY_RUN" to "yes")).dryRun)
    }

    @Test
    fun `missing required env throws`() {
        assertFailsWith<IllegalStateException> {
            loadSettingsFrom(requiredEnv - "PRIORITARR_SONARR_URL")
        }
    }

    @Test
    fun `yaml overlay tunes priority thresholds and intervals`() {
        val yaml = Files.createTempFile("prioritarr-config-", ".yaml").toFile()
        yaml.writeText(
            """
            priority_thresholds:
              p1_watch_pct_min: 0.75
              p2_days_since_watch_max: 42
            intervals:
              reconcile_minutes: 5
            cache:
              priority_ttl_minutes: 30
            audit:
              retention_days: 7
            """.trimIndent()
        )
        val s = loadSettingsFrom(requiredEnv + ("PRIORITARR_CONFIG_PATH" to yaml.absolutePath))
        assertEquals(0.75, s.priorityThresholds.p1WatchPctMin)
        assertEquals(42, s.priorityThresholds.p2DaysSinceWatchMax)
        // Untouched threshold keeps default.
        assertEquals(14, s.priorityThresholds.p1DaysSinceWatchMax)
        assertEquals(5, s.intervals.reconcileMinutes)
        assertEquals(30, s.cache.priorityTtlMinutes)
        assertEquals(7, s.audit.retentionDays)
    }
}
