package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.yoshiz.app.prioritarr.backend.mapping.RefreshStats

/**
 * Summary row for /api/v2/series list. `reason` carries the same
 * string that the priority cache stores for the series — e.g.
 * "watched=0 of aired=100 across 3 monitored season(s)…". UIs can
 * surface it as a tooltip/subtitle without paying a per-row detail
 * fetch.
 */
@Serializable
data class SeriesSummary(
    val id: Long,
    val title: String,
    val tvdbId: Long?,
    val priority: Int?,
    val label: String?,
    val reason: String?,
    val computedAt: String?,
    val managedDownloadCount: Int,
)

@Serializable
data class SeriesDetail(
    val summary: SeriesSummary,
    val priority: PriorityResultWire?,
    val cacheExpiresAt: String?,
    val recentAudit: List<AuditEntry>,
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
