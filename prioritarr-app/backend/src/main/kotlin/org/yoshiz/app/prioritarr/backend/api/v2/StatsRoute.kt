package org.yoshiz.app.prioritarr.backend.api.v2

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.yoshiz.app.prioritarr.backend.app.AppState
import org.yoshiz.app.prioritarr.backend.schemas.StatsResponse

/**
 * GET /api/v2/stats — flat aggregated counters for homepage-style dashboard
 * widgets. Designed to be binding-friendly for gethomepage.dev's customapi
 * widget (each field is a scalar).
 */
fun Route.statsRoute(state: AppState) {
    get("/stats") {
        val priorityCounts: Map<Int, Int> = state.db.q.countByPriority()
            .executeAsList()
            .associate { it.priority.toInt() to it.n.toInt() }

        val downloadCounts = state.db.q.countManagedDownloads().executeAsOne()
        val total = downloadCounts.total.toInt()
        val paused = (downloadCounts.paused ?: 0L).toInt()

        val stats = state.mappings.lastRefreshStats
        val unmatched = stats?.unmatched ?: 0
        val mapped = state.mappings.plexKeyToSeriesId.size

        call.respond(
            StatsResponse(
                liveFollowing = priorityCounts[1] ?: 0,
                caughtUpLapsed = priorityCounts[2] ?: 0,
                fewUnwatched = priorityCounts[3] ?: 0,
                partialBackfill = priorityCounts[4] ?: 0,
                dormant = priorityCounts[5] ?: 0,
                totalCached = priorityCounts.values.sum(),
                managedDownloads = total,
                pausedByUs = paused,
                unmatchedShows = unmatched,
                mappedShows = mapped,
                lastMappingRefreshAt = state.mappings.lastRefreshAt,
                tautulliAvailable = state.mappings.tautulliAvailable,
                dryRun = state.settings.dryRun,
                lastHeartbeat = state.db.getHeartbeat(),
            ),
        )
    }
}
