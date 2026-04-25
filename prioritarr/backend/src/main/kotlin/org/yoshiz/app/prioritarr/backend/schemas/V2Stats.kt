package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable

/**
 * Homepage-friendly aggregated stats. Flat JSON — every field is a scalar
 * so the `customapi` widget can bind each as its own mapping.
 *
 * Homepage widget guide:
 * https://gethomepage.dev/widgets/services/customapi
 */
@Serializable
data class StatsResponse(
    val liveFollowing: Int,          // P1 count
    val caughtUpLapsed: Int,         // P2
    val fewUnwatched: Int,           // P3
    val partialBackfill: Int,        // P4
    val dormant: Int,                // P5
    val totalCached: Int,            // rows in series_priority_cache
    val managedDownloads: Int,       // total rows in managed_downloads
    val pausedByUs: Int,             // count where paused_by_us=1
    val unmatchedShows: Int,         // from last mapping refresh
    val mappedShows: Int,            // plex_key_to_series_id size
    val lastMappingRefreshAt: String?, // ISO +00:00 or null
    val tautulliAvailable: Boolean,
    val dryRun: Boolean,
    val lastHeartbeat: String?,
)
