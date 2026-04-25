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
    /**
     * Next aired-and-missing (or next unaired) monitored episode for
     * the series. Surfaces in the drawer when there's nothing
     * currently downloading — so the UI can read "up next: S02E03"
     * instead of "nothing happening." Null if nothing's coming.
     */
    val nextWantedEpisode: WantedEpisodeWire? = null,
    /**
     * True when the series carries the Sonarr tag configured as
     * `settings.traktUnmonitor.protectTag`. Surfaces in the drawer as
     * a toggle — flipping it adds or removes the tag in Sonarr.
     */
    val protectedFromUnmonitor: Boolean = false,
)

@Serializable
data class WantedEpisodeWire(
    val season: Int,
    val number: Int,
    val title: String,
    val airDateUtc: String?,
    val hasFile: Boolean,
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
    /**
     * Actual state from the download client at query time —
     * downloading / stalledDL / pausedDL / error / missingFiles for
     * qBit; Downloading / Paused / Failed for SAB. Null when the
     * live state couldn't be fetched (client unreachable).
     */
    val liveState: String? = null,
    /** Human-readable status message from the client when available (SAB fail_message, qBit error cause). */
    val liveErrorMessage: String? = null,
    /** Download client deep-link URL for this item, when one can be constructed. */
    val clientUrl: String? = null,
    /**
     * Human-readable labels for each episodeId (e.g. "S02E01 Barrier
     * Day"). Parallel to [episodeIds] — same order. Resolved
     * server-side so the UI doesn't need a second lookup.
     */
    val episodeLabels: List<String> = emptyList(),
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

/**
 * Settings with secrets replaced by the literal "***".
 *
 * `hasOverrides` is true when a DB-persisted override blob exists, so
 * the UI can show "Reset to defaults" and the "restart required to
 * apply" warning when the user has just saved.
 */
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
    val traktClientId: String?,
    val traktClientSecret: String?,
    val traktAccessToken: String?,
    val traktRefreshToken: String?,
    val traktTokenExpiresAt: String?,
    val apiKey: String?,
    val uiOrigin: String?,
    val dryRun: Boolean,
    val logLevel: String,
    val testMode: Boolean,
    val hasOverrides: Boolean = false,
    // Trakt→Sonarr unmonitor reconciler knobs. Live-editable; the
    // scheduler re-reads these every tick.
    val traktUnmonitorEnabled: Boolean = false,
    val traktUnmonitorIntervalHours: Int = 6,
    val traktUnmonitorSkipSpecials: Boolean = false,
    val traktUnmonitorProtectTag: String = "prioritarr-no-unmonitor",
    // Scheduler cadences — see [Intervals]. All live-editable.
    val intervals: IntervalsWire = IntervalsWire(),
    val orphanReaperIntervalMinutes: Int = 60,
    val archiveIntervalHours: Int = 168,
)

/**
 * Wire shape mirrors [org.yoshiz.app.prioritarr.backend.config.Intervals]
 * — defaults match the Kotlin record so the UI's expected baseline
 * stays in sync without an extra round-trip.
 */
@Serializable
data class IntervalsWire(
    val reconcileMinutes: Int = 15,
    val backfillSweepHours: Int = 2,
    val cutoffSweepHours: Int = 24,
    val backfillMaxSearchesPerSweep: Int = 10,
    val backfillDelayBetweenSearchesSeconds: Int = 30,
    val cutoffMaxSearchesPerSweep: Int = 5,
    val refreshMappingsMinutes: Int = 60,
    val refreshSeriesCacheMinutes: Int = 5,
    val refreshEpisodeCacheMinutes: Int = 60,
    val refreshPrioritiesMinutes: Int = 30,
    val queueJanitorMinutes: Int = 30,
    val unmonitoredReaperMinutes: Int = 30,
    val traktTokenRefreshHours: Int = 24,
)

/**
 * One scheduler-tick history row. Status: 'ok' = ran cleanly,
 * 'error' = threw, 'noop' = scheduled but short-circuited (e.g. job
 * disabled).
 */
@Serializable
data class JobRunWire(
    val jobId: String,
    val startedAt: String,
    val finishedAt: String,
    val status: String,
    val durationMs: Long,
    val summary: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class MappingSnapshot(
    val plexKeyToSeriesId: Map<String, Long>,
    val lastRefreshStats: RefreshStats?,
    val tautulliAvailable: Boolean,
)

/**
 * Live bandwidth status for the UI indicator. [effectiveCapBps] is
 * what the policy would enforce right now; [currentTotalBps] is the
 * summed download speed across every tracked torrent; the peak is a
 * cheap auto-calibration ceiling the policy respects.
 */
@Serializable
data class BandwidthStatus(
    val settings: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    val effectiveCapBps: Long,
    val currentTotalBps: Long,
    val observedPeakBps: Long,
    val isPeakWindow: Boolean,
)

/**
 * One log entry surfaced for a specific download. `source` points
 * back to the system that emitted it (sonarr / qbit / sab) so the
 * UI can label rows, e.g. "Sonarr · Grabbed release …".
 */
@Serializable
data class DownloadLogEntry(
    val ts: String,
    val source: String,
    val level: String,
    val message: String,
)

@Serializable
data class DownloadLogsResponse(
    val client: String,
    val clientId: String,
    val entries: List<DownloadLogEntry>,
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

/** Single (season, episode) reference. Used by the sync detail report. */
@Serializable
data class EpisodeRef(val season: Int, val number: Int)

/**
 * Response for PUT /series/{id}/protect-unmonitor — confirms the tag
 * state after toggling. The tag name is echoed so the UI can reflect
 * a rename that happened via settings in the same session.
 */
@Serializable
data class ProtectUnmonitorResponse(
    val seriesId: Long,
    val protected: Boolean,
    val tag: String,
)

/** Bulk variant — `count` is the number of series the tag was applied to. */
@Serializable
data class BulkProtectUnmonitorResponse(
    val count: Int,
    val protected: Boolean,
    val tag: String,
)

/**
 * Per-series cross-source watch sync result. The numbers describe what
 * was *added* on each side after the diff (e.g. plexAdded=3 means three
 * episodes Trakt knew about but Plex didn't, now scrobbled to Plex).
 *
 * [pushedToPlex] / [pushedToTrakt] enumerate the actual episodes — only
 * those that were *attempted* (non-empty even on dry-run). Failed
 * pushes still appear here so the breakdown matches the count; lookup
 * the [errors] list for per-attempt failure detail.
 *
 * [skippedReason] is non-null when one side couldn't be resolved at all
 * (e.g. no plex_key mapping → can't push anywhere on Plex). The series
 * still appears in the report so the UI can show "couldn't sync".
 */
@Serializable
data class SeriesSyncReport(
    val seriesId: Long,
    val title: String,
    val plexAdded: Int = 0,
    val traktAdded: Int = 0,
    val pushedToPlex: List<EpisodeRef> = emptyList(),
    val pushedToTrakt: List<EpisodeRef> = emptyList(),
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

/**
 * Request body for POST /api/v2/priority/preview — choose up to 3
 * series and supply a *partial* threshold patch (any missing field
 * falls back to the current live value). The endpoint runs the full
 * priority compute without touching the cache.
 */
@Serializable
data class PriorityPreviewRequest(
    val seriesIds: List<Long>,
    val thresholds: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

/** Per-series preview result — decision inputs + computed priority. */
@Serializable
data class PriorityPreviewEntry(
    val seriesId: Long,
    val title: String,
    val monitoredSeasons: Int,
    val monitoredEpisodesAired: Int,
    val monitoredEpisodesWatched: Int,
    val unwatched: Int,
    val watchPct: Double,
    val daysSinceWatch: Int?,
    val daysSinceRelease: Int?,
    val previous: PriorityResultWire?,
    val preview: PriorityResultWire,
    /** Per-download: priority (current) + would-it-still-be-paused under preview. */
    val downloads: List<ManagedDownloadPreview>,
)

@Serializable
data class ManagedDownloadPreview(
    val client: String,
    val clientId: String,
    val currentPriority: Int,
    val currentlyPausedByUs: Boolean,
    val wouldBePaused: Boolean,
)

@Serializable
data class PriorityPreviewResponse(
    val thresholds: org.yoshiz.app.prioritarr.backend.config.PriorityThresholds,
    val entries: List<PriorityPreviewEntry>,
)

/**
 * One hit in the Series-page global search result. When the match was
 * on an episode title, [matchedEpisode] carries the SxxExx label + the
 * episode title for UI context ("matched: 'The Avatar Team is Formed'").
 * When matched by series title, [matchedEpisode] is null.
 */
@Serializable
data class SearchHit(
    val seriesId: Long,
    val title: String,
    val matchedBy: String,             // "title" | "episode"
    val matchedEpisode: MatchedEpisode? = null,
)

@Serializable
data class MatchedEpisode(
    val season: Int,
    val number: Int,
    val title: String,
)

@Serializable
data class SearchResponse(
    val query: String,
    val hits: List<SearchHit>,
)

/**
 * One audit row from the OrphanReaper journal. The `action` value
 * encodes the verb (orphan_reaper_delete, _import, _keep, _dry_*,
 * _import_pending, _import_error). `details` is the JSON the reaper
 * wrote — usually `{path, size_bytes, reason}`.
 */
@Serializable
data class OrphanAuditRow(
    val id: Long,
    val ts: String,
    val action: String,
    val details: kotlinx.serialization.json.JsonElement?,
)

@Serializable
data class OrphanPathsRequest(val paths: List<String>)

@Serializable
data class OrphanPathOutcome(val path: String, val ok: Boolean, val message: String? = null)

@Serializable
data class OrphanBulkResult(val total: Int, val succeeded: Int, val outcomes: List<OrphanPathOutcome>)

@Serializable
data class OrphanRenameRequest(val path: String, val newName: String)

@Serializable
data class OrphanRenameResult(val ok: Boolean, val newPath: String? = null, val message: String? = null)

/** Echoed-back probe result so the UI can decide what to do post-rename. */
@Serializable
data class OrphanProbeResult(
    val ok: Boolean,
    val canImport: Boolean,
    val rejections: List<String>,
    val seriesTitle: String? = null,
    val episodes: List<String> = emptyList(),
    val message: String? = null,
)
