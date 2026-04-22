package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.yoshiz.app.prioritarr.backend.mapping.RefreshStats

/**
 * Summary row for /api/v2/series list. `reason` carries the same
 * string that the priority cache stores for the series. UIs surface
 * it as a tooltip/subtitle without paying a per-row detail fetch.
 *
 * `clients` + `pausedCount` come from the series' managed_downloads —
 * the former lets the UI show a "qbit+sab" chip column, the latter
 * drives a "1 of 2 paused" indicator without the UI having to hit
 * /downloads at all. `titleSlug` is Sonarr's own URL slug so the UI
 * can render the title as a direct link into Sonarr.
 */
@Serializable
data class SeriesSummary(
    val id: Long,
    val title: String,
    val titleSlug: String?,
    val tvdbId: Long?,
    val priority: Int?,
    val label: String?,
    val reason: String?,
    val computedAt: String?,
    val managedDownloadCount: Int,
    val clients: List<String>,
    val pausedCount: Int,
)

@Serializable
data class SeriesDetail(
    val summary: SeriesSummary,
    val priority: PriorityResultWire?,
    val cacheExpiresAt: String?,
    val recentAudit: List<AuditEntry>,
    /** Every managed download for this series, each with its own pause state. */
    val downloads: List<ManagedDownloadWire>,
    /** Pre-computed external URLs. Fields that can't be constructed for this deploy are null. */
    val externalLinks: ExternalLinks,
)

/**
 * Deep links to the third-party tools that know about this series.
 * Keep fields nullable — each link construction depends on config
 * that may be absent (e.g. `plex` needs a UI origin, `tautulli` needs
 * the plex_key mapping), and the UI just hides any null entry.
 */
@Serializable
data class ExternalLinks(
    val sonarr: String? = null,
    val trakt: String? = null,
    val tautulli: String? = null,
    val plex: String? = null,
    val qbit: String? = null,
    val sab: String? = null,
)

@Serializable
data class PriorityResultWire(
    val priority: Int,
    val label: String,
    val reason: String,
)

@Serializable
data class ManagedDownloadWire(
    val client: String,
    val clientId: String,
    val seriesId: Long,
    val seriesTitle: String?,
    val episodeIds: List<Long>,
    val initialPriority: Int,
    val currentPriority: Int,
    val pausedByUs: Boolean,
    val firstSeenAt: String,
    val lastReconciledAt: String,
)

@Serializable
data class AuditEntry(
    val id: Long,
    val ts: String,
    val action: String,
    val seriesId: Long?,
    val client: String?,
    val clientId: String?,
    val details: JsonElement?,
)

/** Settings with secrets replaced by the literal "***". */
@Serializable
data class SettingsRedacted(
    val sonarrUrl: String,
    val sonarrApiKey: String,
    val tautulliUrl: String,
    val tautulliApiKey: String,
    val qbitUrl: String,
    val qbitUsername: String?,
    val qbitPassword: String?,
    val sabUrl: String,
    val sabApiKey: String,
    val plexUrl: String?,
    val plexToken: String?,
    val apiKey: String?,
    val uiOrigin: String?,
    val dryRun: Boolean,
    val logLevel: String,
    val testMode: Boolean,
)

@Serializable
data class MappingSnapshot(
    val plexKeyToSeriesId: Map<String, Long>,
    val lastRefreshStats: RefreshStats?,
    val tautulliAvailable: Boolean,
)

/** Standard action response — includes dry_run indicator. */
@Serializable
data class ActionResult(
    val ok: Boolean,
    val dryRun: Boolean = false,
    val message: String? = null,
    val priority: PriorityResultWire? = null,
    val alreadyPaused: Boolean? = null,
    val refreshStats: RefreshStats? = null,
)

/** Reference to a single managed download in bulk requests. */
@Serializable
data class DownloadRef(val client: String, val clientId: String)

/** Request body for POST /api/v2/downloads/bulk. */
@Serializable
data class BulkDownloadActionRequest(
    val action: String,          // pause | resume | boost | demote | untrack
    val items: List<DownloadRef>,
)

/** Per-item outcome in a bulk action. */
@Serializable
data class BulkItemResult(
    val client: String,
    val clientId: String,
    val ok: Boolean,
    val message: String? = null,
)

/**
 * Response body for POST /api/v2/downloads/bulk. Never 4xx/5xx on
 * partial failure — the HTTP call returns 200 with `ok=false` and
 * per-item statuses so the UI can render a mixed-outcome toast.
 */
@Serializable
data class BulkActionResult(
    val ok: Boolean,
    val dryRun: Boolean = false,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val results: List<BulkItemResult>,
)

/**
 * Per-series cross-source watch sync result. The numbers describe what
 * was *added* on each side after the diff (e.g. plexAdded=3 means three
 * episodes Trakt knew about but Plex didn't, now scrobbled to Plex).
 *
 * `skipped` is non-zero when one side couldn't be resolved at all
 * (e.g. no plex_key mapping → can't push anywhere on Plex). The series
 * still appears in the report so the UI can show "couldn't sync".
 */
@Serializable
data class SeriesSyncReport(
    val seriesId: Long,
    val title: String,
    val plexAdded: Int = 0,
    val traktAdded: Int = 0,
    val errors: List<String> = emptyList(),
    val skippedReason: String? = null,
)

/** Aggregated response for POST /api/v2/sync (library-wide). */
@Serializable
data class LibrarySyncReport(
    val ok: Boolean,
    val dryRun: Boolean = false,
    val totalSeries: Int,
    val plexAddedTotal: Int,
    val traktAddedTotal: Int,
    val perSeries: List<SeriesSyncReport>,
)
