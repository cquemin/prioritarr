package org.yoshiz.app.prioritarr.backend

/**
 * Centralised constants + enums for values used in 3+ places. Single
 * source of truth so a rename / API-base change / new status doesn't
 * require touching N call sites and risk silent drift.
 *
 * Single-use literals deliberately stay inline — this file isn't a
 * dumping ground for every string in the codebase.
 */

/** Trakt API root. Single point to change if Trakt ever moves. */
object TraktApi {
    const val BASE_URL = "https://api.trakt.tv"
}

/**
 * Default name for the Sonarr tag that exempts a series from the
 * Trakt→Sonarr unmonitor reconciler. Live setting overrides this; the
 * default is the same on the backend, the frontend hints, and the UI
 * fallback so users see consistent wording everywhere.
 */
const val DEFAULT_PROTECT_TAG = "prioritarr-no-unmonitor"

/**
 * Stable identifiers for every scheduled / event-driven job. Mirror
 * the catalog in `frontend/src/lib/jobs.tsx` — adding a new job means
 * a new entry here AND there. Using these instead of string literals
 * at `trackJob(db, JobId.RECONCILE)` call sites makes a typo a
 * compile error rather than a silent "no row in job_runs" bug.
 */
object JobId {
    const val REFRESH_MAPPINGS = "refresh-mappings"
    const val REFRESH_SERIES_CACHE = "refresh-series-cache"
    const val REFRESH_EPISODE_CACHE = "refresh-episode-cache"
    const val RECONCILE = "reconcile"
    const val REFRESH_PRIORITIES = "refresh-priorities"
    const val QUEUE_JANITOR = "queue-janitor"
    const val UNMONITORED_REAPER = "unmonitored-reaper"
    const val TRAKT_UNMONITOR = "trakt-unmonitor"
    const val TRAKT_TOKEN_REFRESH = "trakt-token-refresh"
    const val WATCHED_ARCHIVER = "watched-archiver"
    const val ORPHAN_REAPER = "orphan-reaper"
    const val BACKFILL_SWEEP = "backfill-sweep"
    const val CUTOFF_SWEEP = "cutoff-sweep"
    const val HEALTH_MONITOR = "health-monitor"
}

/**
 * Status of a single scheduler tick recorded in `job_runs.status`.
 * Stored as the lowercased name so existing rows + the wire format
 * stay byte-identical to the previous string literals.
 */
enum class JobStatus(val wire: String) {
    OK("ok"),
    ERROR("error"),
    /** Scheduled tick that intentionally short-circuited (e.g. job disabled). */
    NOOP("noop"),
}

/**
 * Categorised outcome of a connection test. The UI maps each status
 * to an operator-friendly hint ("Wrong URL or upstream unreachable",
 * etc.), so adding a new status here also requires adding a hint
 * client-side.
 */
enum class ConnectionTestStatus(val wire: String) {
    CONNECTED("connected"),
    /** DNS / refused / timeout — wrong URL or upstream unreachable. */
    CONNECTION_FAILED("connection-failed"),
    /** Reached upstream but credentials rejected. */
    AUTH_FAILED("auth-failed"),
    /** Reached + auth ok, but the response shape doesn't match (wrong path or unsupported version). */
    VERSION_FAILED("version-failed"),
}

/**
 * Wire identifiers for our two download clients. Stored verbatim in
 * `managed_downloads.client` and used as discriminator on every dispatch
 * site. The interface impls in QBitClient / SABClient delegate their
 * `clientName` to these so a rename here propagates to every layer.
 */
enum class DownloadClientName(val wire: String) {
    QBIT("qbit"),
    SAB("sab"),
}

/**
 * Upstream services the connection-test endpoint accepts. The URL
 * slug `/connections/{service}/test` parses straight to one of these.
 * Sonarr is included even though we never store it as a "download
 * client" — the test endpoint covers the full credentials grid.
 */
enum class ConnectionService(val wire: String) {
    SONARR("sonarr"),
    TAUTULLI("tautulli"),
    QBIT("qbit"),
    SAB("sab"),
    PLEX("plex"),
    TRAKT("trakt");

    companion object {
        fun fromWire(s: String?): ConnectionService? =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) }
    }
}

/**
 * Audit-log action codes. Each row in `audit_log.action` carries one
 * of these. Single-use today — but operators grep for the action by
 * name, so a const object beats inline literals scattered across the
 * reconcilers + routes.
 */
object AuditAction {
    /** A reconcile/decision that would have fired but is suppressed by dryRun=true. */
    const val DRY_RUN_ACTION = "dry_run_action"
    /** Stuck torrent / SAB job removed + blocklisted by the queue janitor. */
    const val QUEUE_JANITOR_CLEANUP = "queue_janitor_cleanup"
    /** Same, but with dryRun on. */
    const val QUEUE_JANITOR_DRY_RUN = "queue_janitor_dry_run"
    /** Reconciler set the priority on a download. */
    const val PRIORITY_SET = "priority_set"
    /** Reconciler reordered the SAB queue (priority bands). */
    const val REORDER = "reorder"
    /** Trakt-unmonitor reconciler flipped monitored=false on episodes. */
    const val TRAKT_UNMONITORED = "trakt_unmonitored"
    /** Watched-archiver removed an episode file + unmonitored it. */
    const val WATCHED_ARCHIVED = "watched_archived"
    /** Unmonitored-queue reaper cancelled a Sonarr queue entry. */
    const val UNMONITORED_QUEUE_REMOVED = "unmonitored_queue_removed"
}
