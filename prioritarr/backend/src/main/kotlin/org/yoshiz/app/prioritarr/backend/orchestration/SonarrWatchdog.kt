package org.yoshiz.app.prioritarr.backend.orchestration

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome
import java.time.Instant

private val logger = LoggerFactory.getLogger("SonarrWatchdog")

/**
 * Detects a wedged Sonarr command executor and recovers it via an escalating
 * restart ladder. Stateful across ticks (carries the escalation counters);
 * construct once and call [reconcile] each tick. Dependencies are narrow
 * suspend seams so the loop is unit-testable without HTTP.
 *
 * @param containerRestart null when no docker proxy is configured — the ladder
 *        then stops after the app-restarts (graceful degradation).
 */
class SonarrWatchdog(
    private val getStuckCommands: suspend () -> List<SonarrCommand>,
    private val appRestart: suspend () -> Unit,
    private val containerRestart: (suspend () -> Unit)?,
    private val now: () -> Instant,
    private val cfg: WatchdogConfig,
    private val dryRun: () -> Boolean,
) {
    private var state = WatchdogState()

    suspend fun reconcile(): JobOutcome {
        val stuck = try {
            getStuckCommands()
        } catch (e: Exception) {
            // Likely Sonarr mid-restart / briefly unreachable — skip without touching state.
            logger.warn("sonarr-watchdog: command poll failed, skipping: {}", e.message)
            return JobOutcome(summary = "sonarr unreachable: ${e.message}", noop = true)
        }

        val decision = decideWatchdog(
            wedged = stuck.isNotEmpty(),
            containerAvailable = containerRestart != null,
            state = state,
            now = now(),
            cfg = cfg,
        )
        state = decision.state

        return when (decision.action) {
            WatchdogAction.NONE -> JobOutcome(summary = decision.reason, noop = true)

            WatchdogAction.APP_RESTART -> {
                val ids = stuck.joinToString(",") { it.id.toString() }
                if (dryRun()) {
                    logger.info("sonarr-watchdog: would {} (stuck commands={}) [DRY RUN]", decision.reason, ids)
                } else {
                    logger.warn("sonarr-watchdog: {} (stuck commands={})", decision.reason, ids)
                    appRestart()
                }
                JobOutcome(summary = decision.reason)
            }

            WatchdogAction.CONTAINER_RESTART -> {
                if (dryRun()) {
                    logger.info("sonarr-watchdog: would container-restart sonarr [DRY RUN]")
                } else {
                    logger.warn("sonarr-watchdog: {}", decision.reason)
                    containerRestart!!()
                }
                JobOutcome(summary = decision.reason)
            }

            WatchdogAction.EXHAUSTED -> {
                logger.error("sonarr-watchdog: {}", decision.reason)
                JobOutcome(summary = decision.reason)
            }
        }
    }
}
