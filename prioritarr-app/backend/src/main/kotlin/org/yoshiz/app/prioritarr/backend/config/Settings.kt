package org.yoshiz.app.prioritarr.backend.config

import org.yaml.snakeyaml.Yaml
import java.io.File

/** Priority-compute tuning. Mirrors PriorityThresholds in prioritarr/config.py. */
data class PriorityThresholds(
    val p1WatchPctMin: Double = 0.90,
    val p1DaysSinceWatchMax: Int = 14,
    val p1DaysSinceReleaseMax: Int = 7,
    val p1HiatusGapDays: Int = 14,
    val p1HiatusReleaseWindowDays: Int = 28,
    val p2DaysSinceWatchMax: Int = 60,
    val p3UnwatchedMax: Int = 3,
    val p3DaysSinceWatchMax: Int = 60,
    val p4MinWatched: Int = 1,
)

/** Scheduler cadences. Mirrors Intervals in prioritarr/config.py. */
data class Intervals(
    val reconcileMinutes: Int = 15,
    val backfillSweepHours: Int = 2,
    val cutoffSweepHours: Int = 24,
    val backfillMaxSearchesPerSweep: Int = 10,
    val backfillDelayBetweenSearchesSeconds: Int = 30,
    val cutoffMaxSearchesPerSweep: Int = 5,
)

data class CacheConfig(val priorityTtlMinutes: Int = 60)

data class AuditConfig(
    val retentionDays: Int = 90,
    val webhookDedupeHours: Int = 24,
)

// YAML overlay is parsed as a generic Map<String, Any> — Hoplite was
// silently returning all-nulls on the more strict data-class mapping,
// and we only need four optional sections here. SnakeYAML is the
// transitive dep pulled in by hoplite-yaml; using it directly sidesteps
// the Hoplite auto-binding surprises.

private fun <T : Number> Map<*, *>.num(key: String, coerce: (Number) -> T): T? =
    (this[key] as? Number)?.let(coerce)

/**
 * Top-level application settings. Mirrors Settings in prioritarr/config.py.
 * Required fields have no default and must come from env; optional fields
 * default to `null` / sensible defaults and may be absent.
 */
data class Settings(
    val sonarrUrl: String,
    val sonarrApiKey: String,
    val tautulliUrl: String,
    val tautulliApiKey: String,
    val qbitUrl: String,
    val sabUrl: String,
    val sabApiKey: String,

    val qbitUsername: String? = null,
    val qbitPassword: String? = null,
    val redisUrl: String? = null,
    val plexUrl: String? = null,
    val plexToken: String? = null,

    val dryRun: Boolean = true,
    val logLevel: String = "INFO",
    val testMode: Boolean = false,

    val apiKey: String? = null,
    val uiOrigin: String? = null,

    val configPath: String? = null,

    val priorityThresholds: PriorityThresholds = PriorityThresholds(),
    val intervals: Intervals = Intervals(),
    val cache: CacheConfig = CacheConfig(),
    val audit: AuditConfig = AuditConfig(),
)

private val TRUTHY = setOf("true", "1", "yes")

/** Build a [Settings] from PRIORITARR_* env vars, overlaid with YAML if CONFIG_PATH is set. */
fun loadSettingsFromEnv(): Settings =
    loadSettingsFrom(System.getenv().toMap())

/** Test-friendly overload that reads from an explicit env map. */
fun loadSettingsFrom(envMap: Map<String, String>): Settings {
    fun env(key: String, default: String? = null): String? =
        envMap["PRIORITARR_$key"] ?: default

    fun envRequired(key: String): String =
        env(key) ?: throw IllegalStateException(
            "Required environment variable PRIORITARR_$key is not set."
        )

    val configPath = env("CONFIG_PATH")

    var thresholds = PriorityThresholds()
    var intervals = Intervals()
    var cache = CacheConfig()
    var audit = AuditConfig()

    if (configPath != null && File(configPath).exists()) {
        @Suppress("UNCHECKED_CAST")
        val root = (Yaml().load<Map<String, Any>>(File(configPath).reader())
            ?: emptyMap<String, Any>()) as Map<String, Any>

        (root["priority_thresholds"] as? Map<*, *>)?.let { o ->
            thresholds = thresholds.copy(
                p1WatchPctMin = o.num("p1_watch_pct_min") { it.toDouble() } ?: thresholds.p1WatchPctMin,
                p1DaysSinceWatchMax = o.num("p1_days_since_watch_max") { it.toInt() } ?: thresholds.p1DaysSinceWatchMax,
                p1DaysSinceReleaseMax = o.num("p1_days_since_release_max") { it.toInt() } ?: thresholds.p1DaysSinceReleaseMax,
                p1HiatusGapDays = o.num("p1_hiatus_gap_days") { it.toInt() } ?: thresholds.p1HiatusGapDays,
                p1HiatusReleaseWindowDays = o.num("p1_hiatus_release_window_days") { it.toInt() } ?: thresholds.p1HiatusReleaseWindowDays,
                p2DaysSinceWatchMax = o.num("p2_days_since_watch_max") { it.toInt() } ?: thresholds.p2DaysSinceWatchMax,
                p3UnwatchedMax = o.num("p3_unwatched_max") { it.toInt() } ?: thresholds.p3UnwatchedMax,
                p3DaysSinceWatchMax = o.num("p3_days_since_watch_max") { it.toInt() } ?: thresholds.p3DaysSinceWatchMax,
                p4MinWatched = o.num("p4_min_watched") { it.toInt() } ?: thresholds.p4MinWatched,
            )
        }
        (root["intervals"] as? Map<*, *>)?.let { o ->
            intervals = intervals.copy(
                reconcileMinutes = o.num("reconcile_minutes") { it.toInt() } ?: intervals.reconcileMinutes,
                backfillSweepHours = o.num("backfill_sweep_hours") { it.toInt() } ?: intervals.backfillSweepHours,
                cutoffSweepHours = o.num("cutoff_sweep_hours") { it.toInt() } ?: intervals.cutoffSweepHours,
                backfillMaxSearchesPerSweep = o.num("backfill_max_searches_per_sweep") { it.toInt() } ?: intervals.backfillMaxSearchesPerSweep,
                backfillDelayBetweenSearchesSeconds = o.num("backfill_delay_between_searches_seconds") { it.toInt() } ?: intervals.backfillDelayBetweenSearchesSeconds,
                cutoffMaxSearchesPerSweep = o.num("cutoff_max_searches_per_sweep") { it.toInt() } ?: intervals.cutoffMaxSearchesPerSweep,
            )
        }
        (root["cache"] as? Map<*, *>)?.let { o ->
            cache = cache.copy(priorityTtlMinutes = o.num("priority_ttl_minutes") { it.toInt() } ?: cache.priorityTtlMinutes)
        }
        (root["audit"] as? Map<*, *>)?.let { o ->
            audit = audit.copy(
                retentionDays = o.num("retention_days") { it.toInt() } ?: audit.retentionDays,
                webhookDedupeHours = o.num("webhook_dedupe_hours") { it.toInt() } ?: audit.webhookDedupeHours,
            )
        }
    }

    val dryRun = (env("DRY_RUN", "true") ?: "true").lowercase() !in setOf("false", "0", "no")
    val testMode = (env("TEST_MODE", "false") ?: "false").lowercase() in TRUTHY

    return Settings(
        sonarrUrl = envRequired("SONARR_URL"),
        sonarrApiKey = envRequired("SONARR_API_KEY"),
        tautulliUrl = envRequired("TAUTULLI_URL"),
        tautulliApiKey = envRequired("TAUTULLI_API_KEY"),
        qbitUrl = envRequired("QBIT_URL"),
        sabUrl = envRequired("SAB_URL"),
        sabApiKey = envRequired("SAB_API_KEY"),
        qbitUsername = env("QBIT_USERNAME"),
        qbitPassword = env("QBIT_PASSWORD"),
        redisUrl = env("REDIS_URL"),
        plexUrl = env("PLEX_URL"),
        plexToken = env("PLEX_TOKEN"),
        dryRun = dryRun,
        logLevel = env("LOG_LEVEL", "INFO") ?: "INFO",
        testMode = testMode,
        apiKey = env("API_KEY"),
        uiOrigin = env("UI_ORIGIN"),
        configPath = configPath,
        priorityThresholds = thresholds,
        intervals = intervals,
        cache = cache,
        audit = audit,
    )
}
