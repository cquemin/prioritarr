package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import org.yoshiz.app.prioritarr.backend.mapping.normaliseTitle
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Compute & cache per-series priorities. Tolerant of upstream
 * failures: degrades to a P3 default with
 * reason=dependency_unreachable rather than throwing — webhook
 * handlers must always return a priority per Spec §3.
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

        // Only count watches for episodes in monitored seasons — same
        // rationale as above: a rewatch of a season the user explicitly
        // unmonitored shouldn't inflate the engagement metric for the
        // seasons they actually care about.
        val watchedSe = mutableSetOf<Pair<Int, Int>>()
        for (entry in history) {
            val obj = entry.jsonObject
            val season = obj["parent_media_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val episode = obj["media_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (season != null && episode != null && season in monitoredSeasons) {
                watchedSe.add(season to episode)
            }
            val ts = obj["date"]?.jsonPrimitive?.longOrNull
            if (ts != null && (season == null || season in monitoredSeasons)) {
                val candidate = Instant.ofEpochSecond(ts)
                if (lastWatchedAt == null || candidate.isAfter(lastWatchedAt)) lastWatchedAt = candidate
            }
        }
        val watched = watchedSe.size

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
}
