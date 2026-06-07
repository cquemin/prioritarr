package org.yoshiz.app.prioritarr.backend.orchestration

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.PlexClient
import org.yoshiz.app.prioritarr.backend.clients.TdarrClient
import org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome

private val logger = LoggerFactory.getLogger("TdarrPlexPause")

/**
 * Pause Tdarr's transcode workers while Plex is streaming; resume when
 * idle. Tdarr transcodes on CPU only (no GPU on this host), so a
 * background encode competing with a live Plex transcode starves it of
 * cores and causes playback buffering. While anything is playing we stop
 * Tdarr entirely; once the last stream ends, Tdarr picks the backlog
 * back up on the next tick.
 *
 * Idempotent: only writes to Tdarr when the desired state differs from
 * the current one, so running it every minute is cheap and quiet.
 */
suspend fun reconcileTdarrPause(plex: PlexClient, tdarr: TdarrClient): JobOutcome {
    val sessions = plex.activeSessionCount()
    val shouldPause = sessions > 0
    val isPaused = tdarr.isPaused()

    if (shouldPause == isPaused) {
        return JobOutcome(summary = "no change (plex sessions=$sessions, tdarr paused=$isPaused)", noop = true)
    }

    tdarr.setPaused(shouldPause)
    val verb = if (shouldPause) "paused" else "resumed"
    logger.info("tdarr {} (plex sessions={})", verb, sessions)
    return JobOutcome(summary = "$verb Tdarr (plex sessions=$sessions)")
}
