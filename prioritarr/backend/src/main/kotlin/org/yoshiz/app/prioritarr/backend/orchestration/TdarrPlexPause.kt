package org.yoshiz.app.prioritarr.backend.orchestration

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome

private val logger = LoggerFactory.getLogger("TdarrPlexPause")

/**
 * Default idle polls (the loop ticks every ~60s) required before Tdarr
 * is resumed once Plex goes quiet. ~3 minutes of sustained idle absorbs
 * the mid-playback false-negatives from `/status/sessions` without
 * leaving the CPU idle for long after a stream genuinely ends.
 */
const val DEFAULT_RESUME_AFTER_IDLE_TICKS = 3

/** Outcome of one hysteresis decision: the state to drive Tdarr to, plus the carried idle counter. */
data class PauseDecision(val desiredPaused: Boolean, val idleTicks: Int)

/**
 * Pure hysteresis decision for the Tdarr pause loop. Asymmetric on
 * purpose: pause the instant Plex reports a stream (protects playback),
 * but resume only after [resumeAfterIdleTicks] *consecutive* idle polls.
 *
 * Plex's `/status/sessions` momentarily reports 0 mid-playback — a
 * throttled, fully-buffered transcode stops pinging its timeline, and a
 * seek tears the session down and rebuilds it. Treating a single such
 * false-negative as "stream ended" would un-pause Tdarr, spike the CPU,
 * and starve the live transcode; the next tick re-pauses it. The idle
 * counter debounces that flapping.
 *
 * @param sessions active Plex playback sessions this tick (0 on error).
 * @param currentlyPaused Tdarr's current pauseAllNodes state.
 * @param idleTicks consecutive idle polls observed so far (carried by the caller).
 * @param resumeAfterIdleTicks idle polls required before resuming.
 */
fun decideTdarrPause(
    sessions: Int,
    currentlyPaused: Boolean,
    idleTicks: Int,
    resumeAfterIdleTicks: Int,
): PauseDecision {
    if (sessions > 0) return PauseDecision(desiredPaused = true, idleTicks = 0)
    val newIdle = minOf(idleTicks + 1, resumeAfterIdleTicks)
    val desiredPaused = if (newIdle >= resumeAfterIdleTicks) false else currentlyPaused
    return PauseDecision(desiredPaused = desiredPaused, idleTicks = newIdle)
}

/**
 * Pause Tdarr's transcode workers while Plex is streaming; resume when
 * idle. Tdarr transcodes on CPU only (no GPU on this host), so a
 * background encode competing with a live Plex transcode starves it of
 * cores and causes playback buffering. While anything is playing we stop
 * Tdarr entirely; once the last stream ends — confirmed by several
 * consecutive idle polls, see [decideTdarrPause] — Tdarr picks the
 * backlog back up.
 *
 * Stateful across ticks: the reconciler carries the consecutive-idle
 * counter so a single false-negative from Plex can't flap Tdarr off and
 * back on. Construct once and call [reconcile] each tick. Idempotent:
 * only writes to Tdarr when the desired state differs from the current
 * one, so running it every minute is cheap and quiet.
 *
 * Dependencies are narrow suspend seams (over the Plex/Tdarr clients in
 * production) so the loop is unit-testable without HTTP.
 */
class TdarrPlexPause(
    private val sessionCount: suspend () -> Int,
    private val isPaused: suspend () -> Boolean,
    private val setPaused: suspend (Boolean) -> Unit,
    private val resumeAfterIdleTicks: Int = DEFAULT_RESUME_AFTER_IDLE_TICKS,
) {
    private var idleTicks = 0

    suspend fun reconcile(): JobOutcome {
        val sessions = sessionCount()
        val currentlyPaused = isPaused()
        val decision = decideTdarrPause(sessions, currentlyPaused, idleTicks, resumeAfterIdleTicks)
        idleTicks = decision.idleTicks

        if (decision.desiredPaused == currentlyPaused) {
            return JobOutcome(
                summary = "no change (plex sessions=$sessions, tdarr paused=$currentlyPaused, idle=$idleTicks)",
                noop = true,
            )
        }

        setPaused(decision.desiredPaused)
        val verb = if (decision.desiredPaused) "paused" else "resumed"
        logger.info("tdarr {} (plex sessions={}, idle ticks={})", verb, sessions, idleTicks)
        return JobOutcome(summary = "$verb Tdarr (plex sessions=$sessions, idle=$idleTicks)")
    }
}
