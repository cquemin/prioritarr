package org.yoshiz.app.prioritarr.backend.orchestration

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome
import java.time.Instant

private val logger = LoggerFactory.getLogger("PlexWatchdog")

/** One recently-added Plex item as seen by the watchdog. */
data class PlexRecentItem(val ratingKey: String, val title: String, val addedAt: Instant)

/**
 * Detects Plex items that were added with **zero** media streams (a dead
 * Plex Media Scanner child process) and recovers them: analyze + refresh,
 * then a container restart when analyze changes nothing, then a cooldown.
 * Stateful across ticks; construct once and call [reconcile] each tick.
 * Dependencies are narrow suspend seams so the loop is unit-testable
 * without HTTP. See docs/specs/2026-08-29-plex-watchdog-design.md.
 *
 * @param listRecentItems newest-first items across the watched libraries.
 * @param streamCount number of `<Stream>` elements on an item's media parts.
 * @param activeSessions Plex session count, or null when the probe failed.
 * @param containerRestart null when no docker proxy is configured — the
 *        ladder then stops after the analyze rung.
 */
class PlexWatchdog(
    private val listRecentItems: suspend () -> List<PlexRecentItem>,
    private val streamCount: suspend (ratingKey: String) -> Int,
    private val analyze: suspend (ratingKey: String) -> Unit,
    private val refresh: suspend (ratingKey: String) -> Unit,
    private val activeSessions: suspend () -> Int?,
    private val containerRestart: (suspend () -> Unit)?,
    private val now: () -> Instant,
    private val cfg: () -> PlexWatchdogConfig,
    private val dryRun: () -> Boolean,
) {
    private var state = PlexWatchdogState()

    suspend fun reconcile(): JobOutcome {
        val tick = now()
        val config = cfg()
        val items = try {
            listRecentItems()
        } catch (e: Exception) {
            // Plex mid-restart / unreachable — skip without touching state.
            logger.warn("plex-watchdog: listing recent items failed, skipping: {}", e.message)
            return JobOutcome(summary = "plex unreachable: ${e.message}", noop = true)
        }

        val cutoff = tick.minusSeconds(config.graceMinutes * 60L)
        val broken = mutableListOf<PlexRecentItem>()
        for (item in items) {
            if (item.addedAt.isAfter(cutoff)) continue // may still be copying
            val n = try {
                streamCount(item.ratingKey)
            } catch (e: Exception) {
                logger.warn("plex-watchdog: stream probe failed for {} ({}): {}", item.ratingKey, item.title, e.message)
                continue
            }
            if (n == 0) broken += item
        }

        val sessions = if (broken.isEmpty()) null else activeSessions()
        val decision = decidePlexWatchdog(
            brokenKeys = broken.map { it.ratingKey },
            sessionsActive = sessions?.let { it > 0 },
            containerAvailable = containerRestart != null,
            state = state,
            now = tick,
            cfg = config,
        )
        state = decision.state
        val titles = broken.joinToString(", ") { "${it.ratingKey}=${it.title}" }

        return when (decision.action) {
            PlexWatchdogAction.NONE -> JobOutcome(summary = decision.reason, noop = true)

            PlexWatchdogAction.ANALYZE -> {
                if (dryRun()) {
                    logger.info("plex-watchdog: would {} [{}] [DRY RUN]", decision.reason, titles)
                } else {
                    logger.warn("plex-watchdog: {} [{}]", decision.reason, titles)
                    for (key in decision.keys) {
                        runCatching { analyze(key) }
                            .onFailure { logger.warn("plex-watchdog: analyze {} failed: {}", key, it.message) }
                        runCatching { refresh(key) }
                            .onFailure { logger.warn("plex-watchdog: refresh {} failed: {}", key, it.message) }
                    }
                }
                JobOutcome(summary = decision.reason)
            }

            PlexWatchdogAction.CONTAINER_RESTART -> {
                if (dryRun()) {
                    logger.info("plex-watchdog: would container-restart plex [{}] [DRY RUN]", titles)
                } else {
                    logger.warn("plex-watchdog: {} [{}]", decision.reason, titles)
                    containerRestart!!()
                }
                JobOutcome(summary = decision.reason)
            }

            PlexWatchdogAction.EXHAUSTED -> {
                logger.error("plex-watchdog: {} [{}]", decision.reason, titles)
                JobOutcome(summary = decision.reason)
            }
        }
    }
}
