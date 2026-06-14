package org.yoshiz.app.prioritarr.backend.orchestration

import java.time.Instant

/** Tunables for the watchdog decision. */
data class WatchdogConfig(
    val stallMinutes: Int,
    val confirmChecks: Int,
    val graceMinutes: Int,
    val cooldownMinutes: Int,
)

/** State carried between ticks by the reconciler. */
data class WatchdogState(
    val confirmStreak: Int = 0,
    val appRestartCount: Int = 0,
    val containerRestartDone: Boolean = false,
    val lastActionAt: Instant? = null,
    val cooldownUntil: Instant? = null,
)

enum class WatchdogAction { NONE, APP_RESTART, CONTAINER_RESTART, EXHAUSTED }

data class WatchdogDecision(val action: WatchdogAction, val state: WatchdogState, val reason: String)

/**
 * Pure escalation decision. Act only on a *confirmed* wedge (seen across
 * [WatchdogConfig.confirmChecks] ticks), escalate app-restart x3 then a
 * container-restart, then hold for a cooldown. Recovery (no wedge) resets
 * everything. See docs/specs/2026-06-14-sonarr-watchdog-design.md.
 */
fun decideWatchdog(
    wedged: Boolean,
    containerAvailable: Boolean,
    state: WatchdogState,
    now: Instant,
    cfg: WatchdogConfig,
): WatchdogDecision {
    if (!wedged) {
        return WatchdogDecision(WatchdogAction.NONE, WatchdogState(), "healthy")
    }
    val confirmStreak = minOf(state.confirmStreak + 1, cfg.confirmChecks)
    val s = state.copy(confirmStreak = confirmStreak)

    if (s.cooldownUntil != null && now.isBefore(s.cooldownUntil)) {
        return WatchdogDecision(WatchdogAction.NONE, s, "ladder exhausted; cooldown until ${s.cooldownUntil}")
    }
    if (confirmStreak < cfg.confirmChecks) {
        return WatchdogDecision(WatchdogAction.NONE, s, "wedge seen; confirming (streak=$confirmStreak)")
    }
    if (s.lastActionAt != null && now.isBefore(s.lastActionAt.plusSeconds(cfg.graceMinutes * 60L))) {
        return WatchdogDecision(WatchdogAction.NONE, s, "waiting for last restart to take effect")
    }
    if (s.appRestartCount < 3) {
        val n = s.appRestartCount + 1
        return WatchdogDecision(
            WatchdogAction.APP_RESTART,
            s.copy(appRestartCount = n, lastActionAt = now),
            "app-restart #$n",
        )
    }
    if (!s.containerRestartDone && containerAvailable) {
        return WatchdogDecision(
            WatchdogAction.CONTAINER_RESTART,
            s.copy(containerRestartDone = true, lastActionAt = now),
            "container-restart fallback",
        )
    }
    // exhausted: 3 app-restarts + container (or container unavailable), still wedged
    val cooldownUntil = now.plusSeconds(cfg.cooldownMinutes * 60L)
    val reset = s.copy(
        appRestartCount = 0,
        containerRestartDone = false,
        lastActionAt = null,
        cooldownUntil = cooldownUntil,
    )
    val why = if (containerAvailable) "ladder exhausted" else "app-restarts exhausted; no container fallback configured"
    return WatchdogDecision(WatchdogAction.EXHAUSTED, reset, "$why; cooldown until $cooldownUntil")
}
