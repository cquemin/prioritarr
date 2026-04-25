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
