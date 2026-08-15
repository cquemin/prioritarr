package org.yoshiz.app.prioritarr.backend.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerPrereqRetryTest {

    @Test
    fun prereq_false_reschedules_within_one_minute_not_full_cadence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val runCount = AtomicInteger(0)
        var prereqReady = false
        val outcomes = mutableListOf<Triple<String, String, String?>>()

        val job = JobDefinition(
            id = "test-job",
            cadenceMinutes = { 120L },           // 2 hours
            prerequisites = { prereqReady },
            weight = JobWeight.LIGHT,
            firstRunDelayMinutes = 0,
            run = {
                runCount.incrementAndGet()
                JobOutcome(summary = "ok")
            },
        )

        val scheduler = Scheduler(
            db = freshTempDatabase(),
            jobs = listOf(job),
            outcomeWriter = { id, _, _, status, summary, _ ->
                outcomes += Triple(id, status, summary)
            },
        )

        // Inspect internal state via reflection — assert reschedule
        // window is ≤ 1 minute when prereq fails.
        val nextDueField = scheduler.javaClass.getDeclaredField("nextDue").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val nextDue = nextDueField.get(scheduler) as java.util.concurrent.ConcurrentHashMap<String, Instant>

        // Drive one tick directly (bypassing the 15s start delay).
        val tickFn = scheduler.javaClass.getDeclaredMethod("tick", CoroutineScope::class.java)
            .also { it.isAccessible = true }
        tickFn.invoke(scheduler, scope)

        val due = nextDue["test-job"]!!
        val secondsAhead = java.time.Duration.between(Instant.now(), due).toSeconds()
        assertTrue(secondsAhead in 0..70L, "expected ≤1min reschedule, got ${secondsAhead}s")
        assertEquals(0, runCount.get(), "job must not have run while prereq was false")
        scope.cancel()
    }

    /**
     * Construct a Database backed by a temp file — mirrors P5AttemptsRoundTripTest.
     * Database needs a real path; the scheduler's outcome writes go through
     * outcomeWriter, not the DB, so we never exercise the recordJobRun path.
     */
    private fun freshTempDatabase(): org.yoshiz.app.prioritarr.backend.database.Database {
        val tmp = java.nio.file.Files.createTempFile("prio-sched-test", ".db")
        tmp.toFile().deleteOnExit()
        return org.yoshiz.app.prioritarr.backend.database.Database(tmp.toAbsolutePath().toString())
    }
}

/**
 * Regression tests for the re-entrancy bug that took the stack down on
 * 2026-08-14.
 *
 * [Scheduler.launchJob] only pushed `nextDue` forward in its `finally`
 * block — i.e. *after* the run completed. Any job outliving one 60s tick
 * therefore stayed "due" and was relaunched on every subsequent tick.
 * sub-extract (a full ffprobe walk of ~6664 anime files, >30min) piled up
 * ~30 concurrent sweeps, each spawning unbounded ffprobe children. That
 * pinned prioritarr at 564% CPU / 3.8GB RSS, starved Sonarr's SQLite until
 * its WAL hit 163MB and stopped checkpointing, and left Sonarr so slow that
 * RSS syncs never reached their grab phase (62 started, 1 completed) —
 * releases were logged "accepted" and never downloaded.
 */
class SchedulerReentrancyTest {

    @Test
    fun long_running_job_is_not_relaunched_while_still_in_flight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val job = JobDefinition(
            id = "slow-job",
            cadenceMinutes = { 120L },
            prerequisites = { true },
            weight = JobWeight.LIGHT,
            firstRunDelayMinutes = 0,
            run = {
                entered.incrementAndGet()
                gate.await()          // never completes until we say so
                JobOutcome(summary = "ok")
            },
        )

        val scheduler = Scheduler(
            db = freshTempDatabase(),
            jobs = listOf(job),
            outcomeWriter = { _, _, _, _, _, _ -> },
        )
        val tickFn = scheduler.javaClass.getDeclaredMethod("tick", CoroutineScope::class.java)
            .also { it.isAccessible = true }

        // Tick 1 launches the run; wait until it is genuinely inside run{}.
        tickFn.invoke(scheduler, scope)
        withTimeout(5_000) { while (entered.get() < 1) delay(10) }

        // Ticks 2..5 stand in for the next four 60s ticks while run #1 is
        // still in flight. None of them may start a second run.
        repeat(4) { tickFn.invoke(scheduler, scope) }
        delay(500)

        assertEquals(
            1, entered.get(),
            "job was relaunched while the previous run was still in flight",
        )

        gate.complete(Unit)
        scope.cancel()
    }

    @Test
    fun cadence_is_measured_from_completion_not_from_launch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = AtomicInteger(0)
        val finished = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        // Rescheduling from the *launch* instant means a job whose runtime
        // approaches its cadence is due again the moment it finishes — it then
        // runs back-to-back forever with no idle gap. Next-due must be
        // cadence-after-completion, so a long run still gets its full rest.
        val job = JobDefinition(
            id = "overrunning-job",
            cadenceMinutes = { 1L },
            prerequisites = { true },
            weight = JobWeight.LIGHT,
            firstRunDelayMinutes = 0,
            run = {
                entered.incrementAndGet()
                gate.await()
                JobOutcome(summary = "ok")
            },
        )

        val scheduler = Scheduler(
            db = freshTempDatabase(),
            jobs = listOf(job),
            outcomeWriter = { _, _, _, _, _, _ -> finished.complete(Unit) },
        )
        val nextDueField = scheduler.javaClass.getDeclaredField("nextDue").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val nextDue = nextDueField.get(scheduler) as java.util.concurrent.ConcurrentHashMap<String, Instant>
        val tickFn = scheduler.javaClass.getDeclaredMethod("tick", CoroutineScope::class.java)
            .also { it.isAccessible = true }

        val launchedAt = Instant.now()
        tickFn.invoke(scheduler, scope)
        withTimeout(5_000) { while (entered.get() < 1) delay(10) }

        // Burn a measurable slice of the cadence inside the run, then finish.
        val runMillis = 1_500L
        delay(runMillis)
        gate.complete(Unit)
        withTimeout(5_000) { finished.await() }
        withTimeout(5_000) { while (nextDue["overrunning-job"]!! <= launchedAt) delay(10) }

        // cadence-after-launch would be launchedAt + 60s. cadence-after-completion
        // is launchedAt + runMillis + 60s. Assert we cleared the launch-based mark.
        val aheadOfLaunchMs = java.time.Duration.between(launchedAt, nextDue["overrunning-job"]!!).toMillis()
        assertTrue(
            aheadOfLaunchMs > 60_000L + runMillis / 2,
            "nextDue must be cadence-after-completion; was only ${aheadOfLaunchMs}ms after launch",
        )

        scope.cancel()
    }

    private fun freshTempDatabase(): org.yoshiz.app.prioritarr.backend.database.Database {
        val tmp = java.nio.file.Files.createTempFile("prio-sched-test", ".db")
        tmp.toFile().deleteOnExit()
        return org.yoshiz.app.prioritarr.backend.database.Database(tmp.toAbsolutePath().toString())
    }
}
