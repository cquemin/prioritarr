package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import org.yoshiz.app.prioritarr.backend.database.Database
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Compute & cache per-series priorities. Tolerant of upstream
 * failures: degrades to a P3 default with
 * reason=dependency_unreachable rather than throwing — webhook
 * handlers must always return a priority per Spec §3.
 *
 * Watch history is pluggable via [watchProviders] — Tautulli and/or
 * Trakt and/or anything else in the future. If the list is empty the
 * service just treats the series as "never watched" (all priorities
 * collapse to P5 with watched=0); that's intentional so prioritarr
 * runs without Tautulli + Trakt configured.
 */
class PriorityService(
    private val sonarr: SonarrClient,
    private val watchProviders: List<WatchHistoryProvider>,
    private val db: Database,
    private val thresholds: PriorityThresholds,
    private val cacheTtlMinutes: Long,
) {
    private val logger = LoggerFactory.getLogger(PriorityService::class.java)

    suspend fun priorityForSeries(seriesId: Long): PriorityResult {
        db.getPriorityCache(seriesId)?.let { row ->
            try {
                val expires = OffsetDateTime.parse(row.expires_at).toInstant()
                if (Instant.now() < expires) {
                    return PriorityResult(
                        priority = row.priority.toInt(),
                        label = "P${row.priority} (cached)",
                        reason = row.reason.orEmpty(),
                    )
                }
            } catch (_: Exception) { /* fall through */ }
        }

        val snapshot = try {
            buildSnapshot(seriesId)
        } catch (e: Exception) {
            logger.warn("priorityForSeries: snapshot build failed for $seriesId: ${e.message}")
            null
        }
        if (snapshot == null) {
            return PriorityResult(priority = 3, label = "P3 A few unwatched", reason = "dependency_unreachable")
        }

        val result = computePriority(snapshot, thresholds)
        val now = Database.nowIsoOffset()
        val expires = OffsetDateTime.now().plusMinutes(cacheTtlMinutes).format(Database.ISO_OFFSET)
        db.upsertPriorityCache(
            seriesId = seriesId,
            priority = result.priority.toLong(),
            watchPct = null,
            daysSinceWatch = null,
            unwatchedPending = null,
            computedAt = now,
            expiresAt = expires,
            reason = result.reason,
        )
        return result
    }

    private suspend fun buildSnapshot(seriesId: Long): SeriesSnapshot? {
        val series = sonarr.getSeries(seriesId)
        val episodes = sonarr.getEpisodes(seriesId)

        val title = series["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tvdbId = series["tvdbId"]?.jsonPrimitive?.longOrNull ?: 0L
        val now = Instant.now()

        // Monitored-season set — Sonarr's series.seasons[] has
        // {seasonNumber, monitored}. Episodes whose season is unmonitored
        // are excluded even if the episode itself has monitored=true —
        // the user explicitly opted out of the whole season, so watch
        // percentages computed on the remainder reflect their actual
        // interest. Mirrors Sonarr's own episode-visibility logic.
        val monitoredSeasons: Set<Int> = (series["seasons"] as? JsonArray)
            ?.mapNotNull { el ->
                val s = el.jsonObject
                val n = s["seasonNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val mon = s["monitored"]?.jsonPrimitive?.booleanOrNull == true
                if (n != null && mon) n else null
            }
            ?.toSet()
            ?: emptySet()

        var monitoredAired = 0
        val missing = mutableListOf<Instant>()
        for (epEl in episodes) {
            val ep = epEl.jsonObject
            if (ep["monitored"]?.jsonPrimitive?.booleanOrNull != true) continue
            val seasonNum = ep["seasonNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (seasonNum == null || seasonNum !in monitoredSeasons) continue
            val airStr = ep["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: continue
            val airInstant = try {
                OffsetDateTime.parse(airStr).toInstant()
            } catch (_: Exception) { continue }
            if (airInstant > now) continue
            monitoredAired++
            if (ep["hasFile"]?.jsonPrimitive?.booleanOrNull != true) {
                missing += airInstant
            }
        }
        missing.sort()
        val episodeReleaseDate = missing.lastOrNull()
        val previousEpisodeReleaseDate = if (missing.size >= 2) missing[missing.size - 2] else null

        // Fan out watch-history fetches across every configured provider
        // concurrently. Partial success is fine — e.g. Tautulli fails,
        // Trakt works: use Trakt's data. Only when every configured
        // provider fails do we degrade to dependency_unreachable.
        val ref = SeriesRef(seriesId = seriesId, title = title, tvdbId = tvdbId.takeIf { it > 0 })
        val watchEvents = fetchMergedHistory(ref, seriesId) ?: return null

        // Apply the monitored-season filter after merge. A rewatch of a
        // season the user explicitly unmonitored shouldn't inflate the
        // engagement metric for the seasons they actually care about.
        val inMonitored = watchEvents.filter { it.season in monitoredSeasons }
        val watchedSe = inMonitored.map { it.season to it.episode }.toSet()
        val watched = watchedSe.size
        val lastWatchedAt = inMonitored.maxOfOrNull { it.watchedAt }

        return SeriesSnapshot(
            seriesId = seriesId,
            title = title,
            tvdbId = tvdbId,
            monitoredSeasons = monitoredSeasons.size,
            monitoredEpisodesAired = monitoredAired,
            monitoredEpisodesWatched = watched,
            lastWatchedAt = lastWatchedAt,
            episodeReleaseDate = episodeReleaseDate,
            previousEpisodeReleaseDate = previousEpisodeReleaseDate,
        )
    }

    /**
     * Run every provider's [WatchHistoryProvider.historyFor] in
     * parallel, merge the successes, return `null` if every configured
     * provider failed (caller turns this into dependency_unreachable).
     *
     * If the provider list itself is empty (user configured neither
     * Tautulli nor Trakt) we return an empty list — the caller builds
     * a snapshot with watched=0 and computePriority returns P5. That's
     * the correct behaviour: without a watch-history source there's no
     * engagement signal at all, so everything is full-backfill.
     */
    private suspend fun fetchMergedHistory(ref: SeriesRef, seriesId: Long): List<WatchEvent>? {
        if (watchProviders.isEmpty()) return emptyList()

        val results = coroutineScope {
            watchProviders.map { p -> async { p.name to p.historyFor(ref) } }.awaitAll()
        }

        val successes = results.mapNotNull { (_, res) -> res.getOrNull() }
        if (successes.isEmpty()) {
            logger.info(
                "buildSnapshot: every watch-history provider failed for series {} (providers={})",
                seriesId,
                results.joinToString(", ") { (name, res) -> "$name=${if (res.isFailure) "fail" else "ok"}" },
            )
            return null
        }
        return mergeWatchHistory(successes)
    }
}
