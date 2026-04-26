package org.yoshiz.app.prioritarr.backend.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.JobStatus
import org.yoshiz.app.prioritarr.backend.database.Database
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Reactive job scheduler. One tick loop runs every minute, evaluates
 * which jobs are due AND whose prerequisites are currently satisfied,
 * runs them with a per-tick cap on heavy jobs to prevent dogpiling.
 *
 * Compared with the previous "13 hard-coded coroutines" model:
 *   - Enable a job mid-session (e.g. flip traktUnmonitorEnabled=true)
 *     and the next tick picks it up — no container restart.
 *   - Heavy hitters (refresh-priorities, refresh-mappings, …) are
 *     spread out so we don't melt Sonarr by accident.
 *   - Job catalog is centralised — adding a new job means one
 *     [register] call instead of a new `scope.launch` block.
 *
 * Every job's outcome is persisted via [outcomeWriter] so the
 * Background-jobs page surfaces last-run / status / duration like
 * before.
 */

enum class JobWeight { LIGHT, HEAVY }

data class JobDefinition(
    /** Stable id — must match the entry in `frontend/src/lib/jobs.tsx`. */
    val id: String,
    /** Live-read cadence in minutes. Must be > 0. */
    val cadenceMinutes: () -> Long,
    /**
     * Live-read prerequisite check. Return false to skip this tick
     * (e.g. job disabled, upstream not configured). Cheap lambda —
     * called every tick.
     */
    val prerequisites: () -> Boolean = { true },
    /**
     * Hint for the per-tick concurrency cap. Heavy jobs talk to
     * Sonarr/Trakt with library-wide fanout; we let at most one
     * fire per tick to spread load.
     */
    val weight: JobWeight = JobWeight.LIGHT,
    /**
     * Initial delay before the first run. 0 = run immediately on
     * boot once prereqs are met. Used to stagger heavy hitters off
     * each other on cold start.
     */
    val firstRunDelayMinutes: Long = 0,
    /**
     * The actual work. Returns the [JobOutcome] the scheduler hands
     * to [outcomeWriter] for persistence + logging. Throwing is
     * also fine — the scheduler catches and writes a JobStatus.ERROR
     * row for the operator to inspect.
     */
    val run: suspend () -> JobOutcome,
)

/**
 * Outcome of one tick. Mirrors the existing JobOutcome in Main.kt
 * (we re-declare here so the scheduler module is self-contained).
 */
data class JobOutcome(
    val summary: String? = null,
    /** Scheduler ran the job but it intentionally did nothing (e.g. disabled). */
    val noop: Boolean = false,
)

/**
 * Sink the scheduler hands every completed run to. Identical shape
 * to the existing [Database.recordJobRun] so the integration is a
 * one-liner: `outcomeWriter = db::recordJobRun`.
 */
typealias OutcomeWriter = (
    jobId: String,
    startedAt: Instant,
    finishedAt: Instant,
    status: String,
    summary: String?,
    errorMessage: String?,
) -> Unit

/**
 * Per-tick limit on heavy jobs. Anything above this gets deferred
 * to the next tick (which preserves cadence drift only by minutes).
 */
private const val HEAVY_PER_TICK = 1

/** Tick cadence — small enough to feel responsive, large enough that the loop is cheap. */
private const val TICK_INTERVAL_MS = 60_000L

class Scheduler(
    private val db: Database,
    private val jobs: List<JobDefinition>,
    private val outcomeWriter: OutcomeWriter = { jobId, started, finished, status, summary, error ->
        db.recordJobRun(jobId, started, finished, status, summary, error)
    },
) {
    private val logger = LoggerFactory.getLogger(Scheduler::class.java)

    /**
     * When each job is next eligible. Updated after every run.
     * Concurrent-safe because the launched job coroutines write back
     * after they finish — the tick loop reads in between launches.
     */
    private val nextDue: ConcurrentHashMap<String, Instant> = ConcurrentHashMap()

    init {
        val now = Instant.now()
        for (job in jobs) {
            nextDue[job.id] = now.plus(Duration.ofMinutes(job.firstRunDelayMinutes))
        }
    }

    /**
     * Launch the master tick loop in [scope]. Returns the [Job] so
     * Main.kt can hold a reference; it'll cancel cleanly when the
     * supervisor scope exits.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        // Small initial delay so series_cache + mappings get a chance
        // to populate before the first heavy job fires.
        delay(15_000L)
        while (isActive) {
            try {
                tick(scope)
            } catch (e: Exception) {
                logger.error("scheduler tick crashed (continuing)", e)
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick(scope: CoroutineScope) {
        val now = Instant.now()
        var heavyLaunched = 0

        // Stable order — alphabetical by id — so heavy-hitter caps
        // pick the same jobs each tick (consistent vs. random skips).
        for (job in jobs.sortedBy { it.id }) {
            val due = nextDue[job.id] ?: continue
            if (due.isAfter(now)) continue  // not yet
            // Prerequisites — live re-read each tick, e.g. trakt
            // unmonitor's prerequisite is `traktUnmonitor.enabled`.
            val ready = try { job.prerequisites() } catch (e: Exception) {
                logger.warn("scheduler: prereq check for {} threw: {}", job.id, e.message)
                false
            }
            if (!ready) {
                // Push due forward by one cadence so we don't tight-
                // loop on this prereq every minute. The job will
                // re-evaluate on the next interval.
                rescheduleAfter(job, now)
                continue
            }
            // Heavy-hitter cap — defer extras to next tick.
            if (job.weight == JobWeight.HEAVY && heavyLaunched >= HEAVY_PER_TICK) {
                nextDue[job.id] = now.plus(Duration.ofMinutes(1))
                logger.debug("scheduler: deferring HEAVY {} (cap reached this tick)", job.id)
                continue
            }
            if (job.weight == JobWeight.HEAVY) heavyLaunched++
            launchJob(scope, job, now)
        }
    }

    private fun rescheduleAfter(job: JobDefinition, now: Instant) {
        val mins = job.cadenceMinutes().coerceAtLeast(1L)
        nextDue[job.id] = now.plus(Duration.ofMinutes(mins))
    }

    private fun launchJob(scope: CoroutineScope, job: JobDefinition, scheduledFor: Instant) {
        scope.launch {
            val started = Instant.now()
            try {
                val outcome = job.run()
                val finished = Instant.now()
                outcomeWriter(
                    job.id, started, finished,
                    if (outcome.noop) JobStatus.NOOP.wire else JobStatus.OK.wire,
                    outcome.summary,
                    null,
                )
            } catch (e: Exception) {
                val finished = Instant.now()
                val msg = (e.message ?: e::class.simpleName ?: "unknown").take(2000)
                outcomeWriter(job.id, started, finished, JobStatus.ERROR.wire, null, msg)
                logger.warn("scheduler: {} failed: {}", job.id, e.message)
            } finally {
                rescheduleAfter(job, scheduledFor)
            }
        }
    }
}
