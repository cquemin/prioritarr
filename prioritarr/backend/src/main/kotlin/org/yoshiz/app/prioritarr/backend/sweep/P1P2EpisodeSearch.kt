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

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep.p1p2")

/** One missing episode from Sonarr's /wanted/missing, parsed for the P1/P2 planner. */
internal data class P1P2Episode(
    val episodeId: Long,
    val airDateUtc: String,
    val seasonNumber: Int,
)

/** One P1 or P2 series' worth of candidates, in firing order. */
internal data class P1P2Candidate(
    val seriesId: Long,
    val priority: Int,                        // 1 or 2
    val oldestAirDate: String,
    val episodes: List<P1P2Episode>,          // already sorted by airDateUtc asc and capped
)

/**
 * Pure planner — given the raw missing-episode records, a priority lookup,
 * and exclusion sets, return one [P1P2Candidate] per P1/P2 series whose
 * top-N oldest episodes are not already queued or cooling down. Outer sort
 * is (priority asc, oldestAirDate asc) so P1 wins over P2, oldest wins
 * within each priority.
 */
internal fun buildP1P2Candidates(
    records: JsonArray,
    priorityBySeriesId: Map<Long, Int>,
    queuedEpisodeIds: Set<Long>,
    cooldownEpisodeIds: Set<Long>,
    perSeriesCap: Int,
): List<P1P2Candidate> {
    // Group raw rows by seriesId, keep only P1/P2 series.
    val grouped = mutableMapOf<Long, MutableList<P1P2Episode>>()
    for (row in records) {
        val obj = row.jsonObject
        val sid = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val priority = priorityBySeriesId[sid] ?: continue
        if (priority !in 1..2) continue
        val episodeId = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
        if (episodeId in queuedEpisodeIds) continue
        if (episodeId in cooldownEpisodeIds) continue
        val airDate = obj["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        val season = obj["seasonNumber"]?.jsonPrimitive?.intOrNull ?: 0
        grouped.getOrPut(sid) { mutableListOf() }.add(P1P2Episode(episodeId, airDate, season))
    }

    return grouped.entries.mapNotNull { (sid, eps) ->
        val sorted = eps.sortedBy { it.airDateUtc }.take(perSeriesCap)
        if (sorted.isEmpty()) return@mapNotNull null
        val priority = priorityBySeriesId.getValue(sid)
        P1P2Candidate(
            seriesId = sid,
            priority = priority,
            oldestAirDate = sorted.first().airDateUtc,
            episodes = sorted,
        )
    }.sortedWith(compareBy({ it.priority }, { it.oldestAirDate }))
}

/**
 * Execute [candidates] in order, calling Sonarr's EpisodeSearch and
 * recording per-episode cooldown rows. Each candidate counts as 1
 * against [budget] regardless of how many episode IDs are in its list
 * (one Sonarr command per series). On failure, break and DO NOT
 * record cooldown for the failed call so we retry next sweep.
 *
 * [nowEpochSeconds] is injected so tests can use a frozen clock.
 */
internal suspend fun runP1P2EpisodePass(
    candidates: List<P1P2Candidate>,
    sonarr: SonarrClient,
    db: Database,
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
            logger.info("[backfill-p1p2] DRY RUN: would EpisodeSearch series {} eps {}", c.seriesId, ids)
        } else {
            try {
                sonarr.triggerEpisodeSearch(ids)
            } catch (e: Exception) {
                logger.warn("[backfill-p1p2] EpisodeSearch failed for series {}: {}", c.seriesId, e.message)
                break
            }
            ids.forEach { db.upsertP1P2Attempt(it, nowEpochSeconds) }
            logger.info("[backfill-p1p2] triggered: series {} eps {} (P{})", c.seriesId, ids, c.priority)
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }
    return fired
}
