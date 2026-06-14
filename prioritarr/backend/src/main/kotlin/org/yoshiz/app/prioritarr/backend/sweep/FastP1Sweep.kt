package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import org.yoshiz.app.prioritarr.backend.priority.PriorityService

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep.fastp1")

/**
 * Tight P1-only episode search. For each missing episode of a P1 series
 * whose air date is inside the fast-grab window
 * ([releaseDelayMinutes] .. [windowHours]), fire EpisodeSearch — skipping
 * queued episodes and episodes inside the [Database.BAND_P1_FAST]
 * cooldown ([cooldownMinutes]). Returns the number of EpisodeSearch
 * candidates fired.
 *
 * The window filter runs BEFORE the per-series priority compute so we
 * only pay for `priorityForSeriesFn` on series that actually have a
 * fresh episode.
 */
suspend fun runFastP1Sweep(
    sonarr: SonarrClient,
    db: Database,
    priorityForSeriesFn: suspend (Long) -> PriorityResult,
    releaseDelayMinutes: Int,
    windowHours: Int,
    cooldownMinutes: Int,
    maxPerSweep: Int,
    perSeriesCap: Int = 5,
    dryRun: Boolean,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    searchQueueControl: org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControl? = null,
    cancelBackfillForPriority: Boolean = false,
): Int {
    if (maxPerSweep <= 0) return 0
    val records = try {
        sonarr.getWantedMissing()
    } catch (e: Exception) {
        logger.warn("[fast-p1] fetch failed: {}", e.message); return 0
    }
    if (records.isEmpty()) return 0

    val windowed = filterRecordsByReleaseWindow(records, nowEpochSeconds, releaseDelayMinutes, windowHours)
    if (windowed.isEmpty()) return 0

    val seriesIds = windowed.mapNotNull { it.jsonObject["seriesId"]?.jsonPrimitive?.longOrNull }.toSet()
    val priorityBySeriesId = seriesIds.associateWith { priorityForSeriesFn(it).priority }

    val queuedIds = try { sonarr.getQueue().toEpisodeIdSet() } catch (_: Exception) { emptySet() }
    val cooldownIds = db.listPriorityAttemptedSince(
        Database.BAND_P1_FAST, nowEpochSeconds - cooldownMinutes * 60L,
    ).toSet()

    val candidates = buildPriorityEpisodeCandidates(
        records = windowed,
        priorityBySeriesId = priorityBySeriesId,
        queuedEpisodeIds = queuedIds,
        cooldownEpisodeIds = cooldownIds,
        perSeriesCap = perSeriesCap,
        priorities = 1..1,
    )
    if (candidates.isEmpty()) return 0

    // P1 work exists — clear in-flight backfill searches so these run first.
    if (cancelBackfillForPriority && searchQueueControl != null) {
        searchQueueControl.cancelBackfill()
    }

    logger.info("[fast-p1] {} windowed records, {} candidate series", windowed.size, candidates.size)
    return runPriorityEpisodePass(
        candidates = candidates,
        sonarr = sonarr, db = db, band = Database.BAND_P1_FAST,
        budget = maxPerSweep, delaySeconds = 0, dryRun = dryRun,
        nowEpochSeconds = nowEpochSeconds,
    )
}

/** Production overload that takes a [PriorityService] directly. */
suspend fun runFastP1Sweep(
    sonarr: SonarrClient,
    priorityService: PriorityService,
    db: Database,
    releaseDelayMinutes: Int,
    windowHours: Int,
    cooldownMinutes: Int,
    maxPerSweep: Int,
    dryRun: Boolean,
    searchQueueControl: org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControl? = null,
    cancelBackfillForPriority: Boolean = false,
): Int = runFastP1Sweep(
    sonarr = sonarr, db = db,
    priorityForSeriesFn = priorityService::priorityForSeries,
    releaseDelayMinutes = releaseDelayMinutes,
    windowHours = windowHours,
    cooldownMinutes = cooldownMinutes,
    maxPerSweep = maxPerSweep,
    dryRun = dryRun,
    searchQueueControl = searchQueueControl,
    cancelBackfillForPriority = cancelBackfillForPriority,
)
