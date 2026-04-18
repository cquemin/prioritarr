package org.cquemin.prioritarr.priority

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.cquemin.prioritarr.clients.SonarrClient
import org.cquemin.prioritarr.clients.TautulliClient
import org.cquemin.prioritarr.config.PriorityThresholds
import org.cquemin.prioritarr.database.Database
import org.cquemin.prioritarr.mapping.MappingState
import org.cquemin.prioritarr.mapping.normaliseTitle
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Compute & cache per-series priorities. Mirrors the flow of
 * prioritarr/main.py::_compute_priority_for_series +
 * _build_series_snapshot. Tolerant of upstream failures: degrades to
 * a P3 default with reason=dependency_unreachable (same label as
 * python) rather than throwing — webhook handlers must always return a
 * priority per Spec §3.
 */
class PriorityService(
    private val sonarr: SonarrClient,
    private val tautulli: TautulliClient,
    private val db: Database,
    private val mappings: MappingState,
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

        var monitoredAired = 0
        val missing = mutableListOf<Instant>()
        for (epEl in episodes) {
            val ep = epEl.jsonObject
            if (ep["monitored"]?.jsonPrimitive?.booleanOrNull != true) continue
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

        var watched = 0
        var lastWatchedAt: Instant? = null
        val plexKey = mappings.plexKeyForSeriesTitle(title)

        val history: JsonArray = try {
            if (plexKey != null) {
                tautulli.getHistory(grandparentRatingKey = plexKey, mediaType = "episode", length = 500)
            } else {
                val all = tautulli.getHistory(mediaType = "episode", length = 2000)
                val norm = normaliseTitle(title)
                JsonArray(all.filter {
                    normaliseTitle(it.jsonObject["grandparent_title"]?.jsonPrimitive?.contentOrNull.orEmpty()) == norm
                })
            }
        } catch (e: Exception) {
            logger.info("buildSnapshot: tautulli history failed for series $seriesId: ${e.message}")
            return null
        }

        val watchedSe = mutableSetOf<Pair<Int, Int>>()
        for (entry in history) {
            val obj = entry.jsonObject
            val season = obj["parent_media_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val episode = obj["media_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (season != null && episode != null) watchedSe.add(season to episode)
            val ts = obj["date"]?.jsonPrimitive?.longOrNull
            if (ts != null) {
                val candidate = Instant.ofEpochSecond(ts)
                if (lastWatchedAt == null || candidate.isAfter(lastWatchedAt)) lastWatchedAt = candidate
            }
        }
        watched = watchedSe.size

        return SeriesSnapshot(
            seriesId = seriesId,
            title = title,
            tvdbId = tvdbId,
            monitoredEpisodesAired = monitoredAired,
            monitoredEpisodesWatched = watched,
            lastWatchedAt = lastWatchedAt,
            episodeReleaseDate = episodeReleaseDate,
            previousEpisodeReleaseDate = previousEpisodeReleaseDate,
        )
    }
}
