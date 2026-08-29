package org.yoshiz.app.prioritarr.backend.orchestration

import java.time.Instant

/** Tunables for the Plex analysis watchdog decision. */
data class PlexWatchdogConfig(
    /** An item younger than this is never considered broken (file may still be copying). */
    val graceMinutes: Int,
    /** After requesting an analyze, how long to wait before judging it failed. */
    val analyzeWaitMinutes: Int,
    /** Hold-off after the ladder is exhausted before trying again. */
    val cooldownMinutes: Int,
)

/** State carried between ticks by the reconciler. */
data class PlexWatchdogState(
    /** rating-key → when an analyze was requested for it. */
    val analyzeRequestedAt: Map<String, Instant> = emptyMap(),
    /** Set once the container has been restarted for the current incident. */
    val containerRestartAt: Instant? = null,
    val cooldownUntil: Instant? = null,
)

enum class PlexWatchdogAction { NONE, ANALYZE, CONTAINER_RESTART, EXHAUSTED }

data class PlexWatchdogDecision(
    val action: PlexWatchdogAction,
    val state: PlexWatchdogState,
    val reason: String,
    /** Rating-keys to analyze (+refresh) when [action] is [PlexWatchdogAction.ANALYZE]. */
    val keys: List<String> = emptyList(),
)

/**
 * Pure escalation decision for Plex items that were added but carry **zero**
 * media streams — the signature of the Plex Media Scanner child process
 * failing (observed 2026-08-29: the library DB's `-wal`/`-shm` sidecars
 * vanished from the 9p-mounted /config, so every analysis died with
 * "unable to open database file" and new episodes had no video/audio/subs
 * and refused to play).
 *
 * Ladder:
 *  1. ANALYZE every broken item not yet analyzed (or analyzed before the
 *     last container restart).
 *  2. If a broken item is still broken [PlexWatchdogConfig.analyzeWaitMinutes]
 *     after its analyze, the scanner itself is dead → CONTAINER_RESTART,
 *     but only when Plex is idle ([sessionsActive] == false; `null` =
 *     unknown, treated as busy) and only once per incident.
 *  3. Still broken after the restart + a fresh analyze → EXHAUSTED, hold
 *     for the cooldown.
 * No broken items → NONE and the state resets.
 *
 * @param brokenKeys rating-keys of items older than the grace window with
 *        zero streams, in the caller's preferred order.
 */
fun decidePlexWatchdog(
    brokenKeys: List<String>,
    sessionsActive: Boolean?,
    containerAvailable: Boolean,
    state: PlexWatchdogState,
    now: Instant,
    cfg: PlexWatchdogConfig,
): PlexWatchdogDecision {
    if (brokenKeys.isEmpty()) {
        return PlexWatchdogDecision(PlexWatchdogAction.NONE, PlexWatchdogState(), "healthy")
    }
    if (state.cooldownUntil != null && now.isBefore(state.cooldownUntil)) {
        return PlexWatchdogDecision(
            PlexWatchdogAction.NONE, state,
            "${brokenKeys.size} broken item(s); ladder exhausted, cooldown until ${state.cooldownUntil}",
        )
    }

    // Rung 1: analyze anything we haven't asked Plex to analyze yet for
    // this incident (an analyze issued before a container restart doesn't
    // count — the scanner was dead when it ran).
    val needAnalyze = brokenKeys.filter { key ->
        val at = state.analyzeRequestedAt[key]
        at == null || (state.containerRestartAt != null && at.isBefore(state.containerRestartAt))
    }
    if (needAnalyze.isNotEmpty()) {
        val requested = state.analyzeRequestedAt + needAnalyze.associateWith { now }
        return PlexWatchdogDecision(
            PlexWatchdogAction.ANALYZE,
            state.copy(analyzeRequestedAt = requested),
            "analyze ${needAnalyze.size} item(s) with zero streams",
            keys = needAnalyze,
        )
    }

    // Everything broken has been analyzed at least once this incident.
    val waitSeconds = cfg.analyzeWaitMinutes * 60L
    val stillPending = brokenKeys.any { key ->
        val at = state.analyzeRequestedAt.getValue(key)
        now.isBefore(at.plusSeconds(waitSeconds))
    }
    if (stillPending) {
        return PlexWatchdogDecision(PlexWatchdogAction.NONE, state, "waiting for analyze to take effect")
    }

    // Rung 2: analyze had its chance and changed nothing → scanner is dead.
    if (state.containerRestartAt == null && containerAvailable) {
        return when (sessionsActive) {
            false -> PlexWatchdogDecision(
                PlexWatchdogAction.CONTAINER_RESTART,
                state.copy(containerRestartAt = now),
                "analyze did not restore streams for ${brokenKeys.size} item(s); container-restart plex",
            )
            true -> PlexWatchdogDecision(
                PlexWatchdogAction.NONE, state,
                "scanner looks dead (${brokenKeys.size} item(s)); waiting for Plex to be idle before restart",
            )
            null -> PlexWatchdogDecision(
                PlexWatchdogAction.NONE, state,
                "scanner looks dead (${brokenKeys.size} item(s)); Plex session state unknown, not restarting",
            )
        }
    }

    // Rung 3: restarted (or can't) and still broken → give up for a while.
    val cooldownUntil = now.plusSeconds(cfg.cooldownMinutes * 60L)
    val why = if (containerAvailable) "analyze + container-restart did not help" else "analyze did not help; no container fallback configured"
    return PlexWatchdogDecision(
        PlexWatchdogAction.EXHAUSTED,
        PlexWatchdogState(cooldownUntil = cooldownUntil),
        "$why for ${brokenKeys.size} item(s); cooldown until $cooldownUntil",
    )
}
