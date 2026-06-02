package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import java.time.OffsetDateTime

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep.priority")

internal data class PriorityEpisodeEpisode(
    val episodeId: Long,
    val airDateUtc: String,
    val seasonNumber: Int,
)

internal data class PriorityEpisodeCandidate(
    val seriesId: Long,
    val priority: Int,
    val oldestAirDate: String,
    val episodes: List<PriorityEpisodeEpisode>,
)

/**
 * Pure planner — group missing-episode records by series, keep only
 * series whose priority is in [priorities], filter out queued + cooldown
 * episode IDs, sort each series' episodes by airDate ASC, cap, then sort
 * the outer list by (priority asc, oldestAirDate asc).
 */
internal fun buildPriorityEpisodeCandidates(
    records: JsonArray,
    priorityBySeriesId: Map<Long, Int>,
    queuedEpisodeIds: Set<Long>,
    cooldownEpisodeIds: Set<Long>,
    perSeriesCap: Int,
    priorities: IntRange,
): List<PriorityEpisodeCandidate> {
    val grouped = mutableMapOf<Long, MutableList<PriorityEpisodeEpisode>>()
    for (row in records) {
        val obj = row.jsonObject
        val sid = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val priority = priorityBySeriesId[sid] ?: continue
        if (priority !in priorities) continue
        val episodeId = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
        if (episodeId in queuedEpisodeIds) continue
        if (episodeId in cooldownEpisodeIds) continue
        val airDate = obj["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        val season = obj["seasonNumber"]?.jsonPrimitive?.intOrNull ?: 0
        grouped.getOrPut(sid) { mutableListOf() }.add(PriorityEpisodeEpisode(episodeId, airDate, season))
    }
    return grouped.entries.mapNotNull { (sid, eps) ->
        val sorted = eps.sortedBy { it.airDateUtc }.take(perSeriesCap)
        if (sorted.isEmpty()) return@mapNotNull null
        val priority = priorityBySeriesId.getValue(sid)
        PriorityEpisodeCandidate(
            seriesId = sid,
            priority = priority,
            oldestAirDate = sorted.first().airDateUtc,
            episodes = sorted,
        )
    }.sortedWith(compareBy({ it.priority }, { it.oldestAirDate }))
}

/**
 * Execute [candidates] in order, calling Sonarr's EpisodeSearch and
 * recording per-(band, episode) cooldown rows. Each candidate counts
 * as 1 against [budget] regardless of how many episode IDs the
 * command carries. On failure, break and DO NOT record cooldown for
 * the failed call so we retry next sweep.
 *
 * @param band priority_band value written to priority_episode_attempts
 *             — use [Database.BAND_P1P2] or [Database.BAND_P3P4].
 * @param delaySeconds per-Sonarr-command throttle (expected 0..300).
 *
 * @return count of candidates processed (not strictly count actually
 *         grabbed — dry-run candidates within budget are counted;
 *         on mid-loop failure the failed one is NOT counted).
 */
internal suspend fun runPriorityEpisodePass(
    candidates: List<PriorityEpisodeCandidate>,
    sonarr: SonarrClient,
    db: Database,
    band: String,
    budget: Int,
    delaySeconds: Int,
    dryRun: Boolean,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): Int {
    var fired = 0
    for (c in candidates) {
        if (fired >= budget) break
        val ids = c.episodes.map { it.episodeId }
        if (dryRun) {
            logger.info("[backfill-{}] DRY RUN: would EpisodeSearch series {} eps {}", band, c.seriesId, ids)
        } else {
            try {
                sonarr.triggerEpisodeSearch(ids)
            } catch (e: Exception) {
                logger.warn("[backfill-{}] EpisodeSearch failed for series {}: {}", band, c.seriesId, e.message)
                break
            }
            ids.forEach { db.upsertPriorityAttempt(band, it, nowEpochSeconds) }
            logger.info("[backfill-{}] triggered: series {} eps {} (P{})", band, c.seriesId, ids, c.priority)
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }
    return fired
}

/**
 * Keep only missing-episode records whose own air date falls inside the
 * fast-grab window: at least [minAgeMinutes] old (don't search before
 * fansubs post) and at most [maxAgeHours] old (after that the normal 2h
 * backfill takes over). Rows with a missing/unparseable airDateUtc are
 * dropped — the fast path only acts on episodes it can place in time.
 */
internal fun filterRecordsByReleaseWindow(
    records: JsonArray,
    nowEpochSeconds: Long,
    minAgeMinutes: Int,
    maxAgeHours: Int,
): JsonArray {
    val minAgeSec = minAgeMinutes * 60L
    val maxAgeSec = maxAgeHours * 3600L
    return JsonArray(
        records.filter { row ->
            val air = row.jsonObject["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: return@filter false
            val airEpoch = try { OffsetDateTime.parse(air).toEpochSecond() } catch (_: Exception) { return@filter false }
            val age = nowEpochSeconds - airEpoch
            age in minAgeSec..maxAgeSec
        },
    )
}
