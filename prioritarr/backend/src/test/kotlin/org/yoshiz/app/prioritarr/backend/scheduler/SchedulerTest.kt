package org.yoshiz.app.prioritarr.backend.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
