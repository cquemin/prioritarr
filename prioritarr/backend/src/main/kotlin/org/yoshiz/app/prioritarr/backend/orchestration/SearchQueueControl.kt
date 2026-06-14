package org.yoshiz.app.prioritarr.backend.orchestration

import org.slf4j.LoggerFactory

private val sqcLogger = LoggerFactory.getLogger("SearchQueueControl")

/** Backfill search command types — cancellable. EpisodeSearch (P1/P2) is deliberately excluded. */
val BACKFILL_SEARCH_COMMANDS = setOf("SeriesSearch", "SeasonSearch", "CutoffUnmetSearch")

/** All search command types — counted for congestion. */
val ALL_SEARCH_COMMANDS = BACKFILL_SEARCH_COMMANDS + "EpisodeSearch"

private val ACTIVE_STATUSES = setOf("queued", "started")

/** Active (queued/started) search commands of any type — the congestion signal. */
fun pendingSearchCount(commands: List<SonarrCommand>): Int =
    commands.count { it.name in ALL_SEARCH_COMMANDS && it.status in ACTIVE_STATUSES }

/** Ids of active backfill searches (Series/Season/CutoffUnmet). Never an EpisodeSearch. */
fun cancellableBackfillSearchIds(commands: List<SonarrCommand>): List<Long> =
    commands.filter { it.name in BACKFILL_SEARCH_COMMANDS && it.status in ACTIVE_STATUSES }.map { it.id }

/**
 * Reads Sonarr's command queue to throttle/preempt backfill searches.
 * Seam-injected (no HTTP) so it is unit-testable. Fails safe: a command-poll
 * error reports not-congested and cancels nothing, so sweeps proceed as before.
 */
class SearchQueueControl(
    private val getCommands: suspend () -> List<SonarrCommand>,
    private val cancelCommand: suspend (Long) -> Unit,
    private val threshold: () -> Int,
    private val dryRun: () -> Boolean,
) {
    suspend fun isCongested(): Boolean = try {
        pendingSearchCount(getCommands()) >= threshold()
    } catch (e: Exception) {
        sqcLogger.warn("isCongested: command poll failed, treating as uncongested: {}", e.message)
        false
    }

    /** Cancel active backfill searches (never EpisodeSearch). Returns the count targeted. */
    suspend fun cancelBackfill(): Int {
        val ids = try {
            cancellableBackfillSearchIds(getCommands())
        } catch (e: Exception) {
            sqcLogger.warn("cancelBackfill: command poll failed: {}", e.message)
            return 0
        }
        if (ids.isEmpty()) return 0
        if (dryRun()) {
            sqcLogger.info("sonarr search control: would cancel {} backfill searches {} [DRY RUN]", ids.size, ids)
        } else {
            sqcLogger.info("sonarr search control: cancelling {} backfill searches {} to prioritise P1/P2", ids.size, ids)
            ids.forEach { runCatching { cancelCommand(it) }.onFailure { e -> sqcLogger.warn("cancel {} failed: {}", it, e.message) } }
        }
        return ids.size
    }
}
