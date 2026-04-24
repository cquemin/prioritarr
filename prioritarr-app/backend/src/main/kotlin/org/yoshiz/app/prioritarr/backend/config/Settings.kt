package org.yoshiz.app.prioritarr.backend.config

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Priority-compute tuning.
 *
 * P1/P2/P3 all use OR gates over two engagement metrics:
 *   (watchPct ≥ pN_watch_pct_min)  OR  (unwatched ≤ p3_unwatched_max)
 * So a user with a few unwatched episodes is "engaged" both on a long
 * show (3/100 left = 97%) and on a short show (3/12 left = 75%).
 *
 * Release recency gates whether P1 is accessible:
 *   release ≤ p1_days_since_release_max  → P1 gate open
 *   release ≤ p1_hiatus_release_window_days AND hiatus detected → P1 gate open
 *   else → P1 gate closed
 *
 * These defaults reproduce the original single-metric behaviour for
 * shows at the ≥90% / ≥80% / ≥75% watch-percent extremes; they only
 * widen things for shows with small absolute unwatched counts that
 * previously got stuck in P4.
 */
@kotlinx.serialization.Serializable
data class PriorityThresholds(
    val p1WatchPctMin: Double = 0.90,
    val p1DaysSinceWatchMax: Int = 14,
    val p1DaysSinceReleaseMax: Int = 7,
    val p1HiatusGapDays: Int = 14,
    val p1HiatusReleaseWindowDays: Int = 28,
    val p2WatchPctMin: Double = 0.80,
    val p2DaysSinceWatchMax: Int = 60,
    val p3WatchPctMin: Double = 0.75,
    val p3UnwatchedMax: Int = 3,
    val p3DaysSinceWatchMax: Int = 60,
    val p4MinWatched: Int = 1,
    /**
     * When true, a series with zero missing monitored-aired episodes
     * (every aired episode has a file) short-circuits to P5
     * regardless of engagement. Meant to suppress "caught up but
     * lapsed" shows that prioritarr can't actually do anything about
     * until a new episode drops. Off = keep the old engagement-only
     * behaviour.
     */
    val p5WhenNothingToDownload: Boolean = true,
    /**
     * For the "dormant show has new content" band: if the user is
     * mostly caught up (>= p2_watch_pct_min) AND missing > 0 AND
     * the latest release lands within this many days, the show is
     * rescued from P5 into P3 — there's something to grab, user
     * was historically engaged, just needs prioritarr to kick off a
     * search. 0 disables the band entirely.
     */
    val p3DormantReleaseWindowDays: Int = 60,
)

/** Scheduler cadences. */
data class Intervals(
    val reconcileMinutes: Int = 15,
    val backfillSweepHours: Int = 2,
    val cutoffSweepHours: Int = 24,
    val backfillMaxSearchesPerSweep: Int = 10,
    val backfillDelayBetweenSearchesSeconds: Int = 30,
    val cutoffMaxSearchesPerSweep: Int = 5,
)

data class CacheConfig(val priorityTtlMinutes: Int = 60)

/**
 * Bandwidth-aware enforcement knobs. Controls when + whether to
 * pause lower-priority torrents in favour of higher-priority ones
 * based on the actual link utilisation, not just priority alone.
 *
 * Effective cap is picked at each reconcile tick:
 *   quiet mode enabled  -> quiet_mode_max_mbps
 *   inside peak window  -> peak_hours_max_mbps (when set)
 *   otherwise           -> min(max_mbps, observed_peak_mbps * 1.1)
 *
 * Set max_mbps = 0 to disable the whole feature (reverts to the
 * original simple pause-band rule).
 */
@kotlinx.serialization.Serializable
data class BandwidthSettings(
    /** Your line's advertised/real capacity in megabits per second. 0 disables the feature. */
    val maxMbps: Int = 0,
    /** Rolling observed peak the policy may auto-calibrate against. Filled by the telemetry layer; ignored when 0. */
    val observedPeakMbps: Int = 0,
    /** Utilisation at which we start considering pausing lower bands (default 90%). */
    val utilisationThresholdPct: Double = 0.90,
    /** If a lower-band torrent would finish inside this window, don't interrupt it. */
    val etaBufferMinutes: Int = 10,
    /** When P1's observed speed stays below this, it's peer-limited; pausing P4/P5 wouldn't help. */
    val p1MinSpeedKbps: Int = 100,
    /** Peak-hours profile. "HH:MM" start/end (inclusive-exclusive); null disables. */
    val peakHoursStart: String? = null,
    val peakHoursEnd: String? = null,
    val peakHoursMaxMbps: Int? = null,
    /** Manual "I'm streaming / on a call" override; when on, caps at [quietModeMaxMbps]. */
    val quietModeEnabled: Boolean = false,
    val quietModeMaxMbps: Int = 100,
)

data class AuditConfig(
    val retentionDays: Int = 90,
    val webhookDedupeHours: Int = 24,
)

/**
 * Archive-watched-episodes feature. Periodically sweeps the library
 * and removes episodes that have been watched AND fall outside the
 * "keep" window (latest season, or last N episodes if that season
 * has more than [latestSeasonMaxEpisodes]). Removed episodes are
 * also set monitored=false in Sonarr so prioritarr's backfill /
 * cutoff sweeps don't immediately re-grab them.
 *
 * Safety:
 *   - Unwatched episodes are NEVER touched.
 *   - Default OFF; operator opts in via the UI or YAML.
 *   - Every action is audit-logged.
 *   - Dry-run mode enumerates without writing.
 */
@kotlinx.serialization.Serializable
data class ArchiveSettings(
    val watchedEnabled: Boolean = false,
    val latestSeasonMaxEpisodes: Int = 30,
    val intervalHours: Int = 168,   // weekly by default
)

// YAML overlay is parsed as a generic Map<String, Any> — Hoplite was
// silently returning all-nulls on the more strict data-class mapping,
// and we only need four optional sections here. SnakeYAML is the
// transitive dep pulled in by hoplite-yaml; using it directly sidesteps
// the Hoplite auto-binding surprises.

private fun <T : Number> Map<*, *>.num(key: String, coerce: (Number) -> T): T? =
    (this[key] as? Number)?.let(coerce)

/**
 * Top-level application settings. Required fields have no default and
 * must come from env; optional fields default to `null` / sensible
 * defaults and may be absent.
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
    val plexUrl: String? = null,
    val plexToken: String? = null,

    // Trakt OAuth credentials. Both must be set for the Trakt provider
    // to be installed; either missing = Trakt disabled, Tautulli alone.
    val traktClientId: String? = null,
    val traktAccessToken: String? = null,

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
    val bandwidth: BandwidthSettings = BandwidthSettings(),
    val archive: ArchiveSettings = ArchiveSettings(),

    /** Paths swept by the OrphanReaper. Empty list disables the reaper. */
    val orphanReaperPaths: List<String> = listOf(
        "/storage/torrents/series",
        "/storage/torrents/movies",
        "/storage/usenet/incomplete",
        "/storage/usenet/complete/series",
        "/storage/usenet/complete/movies",
    ),
    /** OrphanReaper sweep cadence in minutes. */
    val orphanReaperIntervalMinutes: Int = 60,
)

/**
 * Subset of [Settings] safe for runtime editing via the Settings UI.
 *
 * Excluded on purpose:
 *   - `apiKey` (the X-Api-Key for /api/v2 — letting the UI rotate it
 *     would lock the user out the moment they save)
 *   - `testMode` (security; env-only)
 *   - `configPath` (env-only path to YAML)
 *   - `priorityThresholds`, `intervals`, `cache`, `audit` (each lives
 *     in its own dedicated UI — thresholds already, intervals/audit
 *     stay env-controlled for now)
 *
 * Every field is nullable: a null in the DB blob means "leave the
 * baseline alone". A non-null value overrides the baseline. Secrets in
 * the GET response are masked to "***" so the UI can show them
 * without leaking them; the UI sends back null (or omits the field)
 * to mean "no change", and a real string to overwrite.
 */
@kotlinx.serialization.Serializable
data class EditableSettings(
    val sonarrUrl: String? = null,
    val sonarrApiKey: String? = null,
    val tautulliUrl: String? = null,
    val tautulliApiKey: String? = null,
    val qbitUrl: String? = null,
    val qbitUsername: String? = null,
    val qbitPassword: String? = null,
    val sabUrl: String? = null,
    val sabApiKey: String? = null,
    val plexUrl: String? = null,
    val plexToken: String? = null,
    val traktClientId: String? = null,
    val traktAccessToken: String? = null,
    val dryRun: Boolean? = null,
    val logLevel: String? = null,
    val uiOrigin: String? = null,
)

/** Apply [override] on top of [base], returning a new [Settings]. */
fun applySettingsOverride(base: Settings, override: EditableSettings): Settings = base.copy(
    sonarrUrl = override.sonarrUrl ?: base.sonarrUrl,
    sonarrApiKey = override.sonarrApiKey ?: base.sonarrApiKey,
    tautulliUrl = override.tautulliUrl ?: base.tautulliUrl,
    tautulliApiKey = override.tautulliApiKey ?: base.tautulliApiKey,
    qbitUrl = override.qbitUrl ?: base.qbitUrl,
    qbitUsername = override.qbitUsername ?: base.qbitUsername,
    qbitPassword = override.qbitPassword ?: base.qbitPassword,
    sabUrl = override.sabUrl ?: base.sabUrl,
    sabApiKey = override.sabApiKey ?: base.sabApiKey,
    plexUrl = override.plexUrl ?: base.plexUrl,
    plexToken = override.plexToken ?: base.plexToken,
    traktClientId = override.traktClientId ?: base.traktClientId,
    traktAccessToken = override.traktAccessToken ?: base.traktAccessToken,
    dryRun = override.dryRun ?: base.dryRun,
    logLevel = override.logLevel ?: base.logLevel,
    uiOrigin = override.uiOrigin ?: base.uiOrigin,
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
    var bandwidth = BandwidthSettings()
    var archive = ArchiveSettings()

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
                p2WatchPctMin = o.num("p2_watch_pct_min") { it.toDouble() } ?: thresholds.p2WatchPctMin,
                p2DaysSinceWatchMax = o.num("p2_days_since_watch_max") { it.toInt() } ?: thresholds.p2DaysSinceWatchMax,
                p3WatchPctMin = o.num("p3_watch_pct_min") { it.toDouble() } ?: thresholds.p3WatchPctMin,
                p3UnwatchedMax = o.num("p3_unwatched_max") { it.toInt() } ?: thresholds.p3UnwatchedMax,
                p3DaysSinceWatchMax = o.num("p3_days_since_watch_max") { it.toInt() } ?: thresholds.p3DaysSinceWatchMax,
                p4MinWatched = o.num("p4_min_watched") { it.toInt() } ?: thresholds.p4MinWatched,
                p5WhenNothingToDownload = (o["p5_when_nothing_to_download"] as? Boolean) ?: thresholds.p5WhenNothingToDownload,
                p3DormantReleaseWindowDays = o.num("p3_dormant_release_window_days") { it.toInt() } ?: thresholds.p3DormantReleaseWindowDays,
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
        (root["archive"] as? Map<*, *>)?.let { o ->
            archive = archive.copy(
                watchedEnabled = (o["watched_enabled"] as? Boolean) ?: archive.watchedEnabled,
                latestSeasonMaxEpisodes = o.num("latest_season_max_episodes") { it.toInt() } ?: archive.latestSeasonMaxEpisodes,
                intervalHours = o.num("interval_hours") { it.toInt() } ?: archive.intervalHours,
            )
        }
        (root["bandwidth"] as? Map<*, *>)?.let { o ->
            bandwidth = bandwidth.copy(
                maxMbps = o.num("max_mbps") { it.toInt() } ?: bandwidth.maxMbps,
                utilisationThresholdPct = o.num("utilisation_threshold_pct") { it.toDouble() } ?: bandwidth.utilisationThresholdPct,
                etaBufferMinutes = o.num("eta_buffer_minutes") { it.toInt() } ?: bandwidth.etaBufferMinutes,
                p1MinSpeedKbps = o.num("p1_min_speed_kbps") { it.toInt() } ?: bandwidth.p1MinSpeedKbps,
                peakHoursStart = o["peak_hours_start"] as? String ?: bandwidth.peakHoursStart,
                peakHoursEnd = o["peak_hours_end"] as? String ?: bandwidth.peakHoursEnd,
                peakHoursMaxMbps = o.num("peak_hours_max_mbps") { it.toInt() } ?: bandwidth.peakHoursMaxMbps,
                quietModeEnabled = (o["quiet_mode_enabled"] as? Boolean) ?: bandwidth.quietModeEnabled,
                quietModeMaxMbps = o.num("quiet_mode_max_mbps") { it.toInt() } ?: bandwidth.quietModeMaxMbps,
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
        plexUrl = env("PLEX_URL"),
        plexToken = env("PLEX_TOKEN"),
        traktClientId = env("TRAKT_CLIENT_ID"),
        traktAccessToken = env("TRAKT_ACCESS_TOKEN"),
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
        bandwidth = bandwidth,
        archive = archive,
    )
}
