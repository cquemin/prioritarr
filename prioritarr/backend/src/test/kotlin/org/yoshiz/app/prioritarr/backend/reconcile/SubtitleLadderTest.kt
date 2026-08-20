package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the fixture's fake `saveState` captures for one episode. */
private data class SavedState(
    val rung: String,
    val attempts: Long,
    val nextRetryAt: String?,
    val upstreamDowns: Long = 0,
)

class SubtitleLadderTest {

    private fun candidate(dir: Path, name: String, priority: Int = 1) =
        LadderCandidate(
            videoPath = dir.resolve("$name.mkv"),
            seriesId = 236L,
            episodeId = 25749L,
            priority = priority,
            audioLang = "ja",
        )

    private fun ladder(
        candidates: suspend () -> List<LadderCandidate> = { emptyList() },
        extractEmbedded: suspend (Path) -> Boolean = { false },
        hasEmbedded: suspend (Path) -> Boolean = { false },
        bazarr: suspend (Long, Long) -> Boolean = { _, _ -> false },
        whisper: suspend (Path, String?) -> String? = { _, _ -> null },
        plexSessions: suspend () -> Int? = { 0 },
        congested: suspend () -> Boolean = { false },
        whisperEnabled: Boolean = true,
        state: MutableMap<Long, SavedState> = mutableMapOf(),
    ) = SubtitleLadder(
        candidates = candidates,
        hasEmbeddedEnglishText = hasEmbedded,
        extractEmbedded = extractEmbedded,
        triggerBazarr = bazarr,
        whisperTranslate = whisper,
        plexSessions = plexSessions,
        congested = congested,
        maxPerSweep = { 10 },
        whisperEnabled = { whisperEnabled },
        whisperMaxPriority = { 2 },
        resumeAfterIdleTicks = { 1 },
        loadState = { id -> state[id]?.let { LadderState(it.rung, it.attempts, it.upstreamDowns) } },
        saveState = { id, rung, _, attempts, nextRetryAt, upstreamDowns ->
            state[id] = SavedState(rung, attempts, nextRetryAt, upstreamDowns)
        },
    )

    private fun hoursFromNow(iso: String?): Double {
        val at = OffsetDateTime.parse(iso!!)
        return java.time.Duration.between(OffsetDateTime.now(ZoneOffset.UTC), at).toMinutes() / 60.0
    }

    @Test
    fun existing_en_srt_short_circuits_without_touching_any_rung() = runBlocking {
        val dir = Files.createTempDirectory("ladder-satisfied")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        Files.writeString(dir.resolve("Ep.en.srt"), "1\n")

        val l = ladder(
            hasEmbedded = { error("must not probe a satisfied file") },
            bazarr = { _, _ -> error("must not call bazarr") },
            whisper = { _, _ -> error("must not call whisper") },
        )

        assertEquals(LadderOutcome.SATISFIED, l.runOne(candidate(dir, "Ep")))
    }

    @Test
    fun satisfied_outcome_schedules_a_future_reverify_not_due_now() = runBlocking {
        // null in next_retry_at means "due now" (ladderEpisodesDue selects
        // next_retry_at IS NULL OR next_retry_at <= ?). If SATISFIED wrote
        // null, an already-done episode would be a candidate on every
        // single sweep, forever, ahead of episodes that actually need work.
        val dir = Files.createTempDirectory("ladder-satisfied-retry")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        Files.writeString(dir.resolve("Ep.en.srt"), "1\n")

        val state = mutableMapOf<Long, SavedState>()
        val l = ladder(state = state)

        assertEquals(LadderOutcome.SATISFIED, l.runOne(candidate(dir, "Ep")))

        val saved = state.getValue(25749L)
        val nextRetryAt = saved.nextRetryAt
        assertTrue(nextRetryAt != null, "SATISFIED must not store a null next_retry_at")
        // ISO_OFFSET pins the zero-offset suffix to +00:00, never the JDK
        // default 'Z' — a 'Z' row would sort below '+' and never compare
        // as due again.
        assertTrue(nextRetryAt!!.matches(Regex(""".*\+00:00$""")), "unexpected timestamp format: $nextRetryAt")
        assertTrue(
            OffsetDateTime.parse(nextRetryAt).isAfter(OffsetDateTime.now(ZoneOffset.UTC)),
            "SATISFIED must schedule a future re-verify, not due-now: $nextRetryAt",
        )
    }

    @Test
    fun variant_takes_the_free_rung_but_never_whisper() = runBlocking {
        val dir = Files.createTempDirectory("ladder-variant")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        Files.writeString(dir.resolve("Ep.en.hi.srt"), "1\n")

        var extracted = false
        val l = ladder(
            hasEmbedded = { true },
            extractEmbedded = { extracted = true; true },
            whisper = { _, _ -> error("variant must block whisper") },
        )

        assertEquals(LadderOutcome.SATISFIED, l.runOne(candidate(dir, "Ep")))
        assertTrue(extracted)
    }

    @Test
    fun variant_with_no_embedded_track_is_blocked_not_whispered() = runBlocking {
        val dir = Files.createTempDirectory("ladder-variant-blocked")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        Files.writeString(dir.resolve("Ep.srt"), "1\n")

        val l = ladder(
            hasEmbedded = { false },
            bazarr = { _, _ -> error("variant must block bazarr") },
            whisper = { _, _ -> error("variant must block whisper") },
        )

        assertEquals(LadderOutcome.BLOCKED_VARIANT, l.runOne(candidate(dir, "Ep")))
    }

    @Test
    fun climbs_to_whisper_and_writes_the_sidecar() = runBlocking {
        val dir = Files.createTempDirectory("ladder-whisper")
        Files.writeString(dir.resolve("Ep.mkv"), "x")

        // Second pass: bazarr already tried, so the ladder reaches whisper.
        val state = mutableMapOf(25749L to SavedState("R2_BAZARR", 1L, null))
        var seenLang: String? = "unset"
        val l = ladder(
            hasEmbedded = { false },
            whisper = { _, lang -> seenLang = lang; "1\n00:00:01,000 --> 00:00:02,000\nHello\n" },
            state = state,
        )

        assertEquals(LadderOutcome.SATISFIED, l.runOne(candidate(dir, "Ep")))
        assertEquals("ja", seenLang)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nHello\n",
            Files.readString(dir.resolve("Ep.en.srt")),
        )
    }

    @Test
    fun whisper_failure_is_upstream_down_and_writes_nothing() = runBlocking {
        val dir = Files.createTempDirectory("ladder-whisper-fail")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        val state = mutableMapOf(25749L to SavedState("R2_BAZARR", 1L, null))

        val l = ladder(hasEmbedded = { false }, whisper = { _, _ -> null }, state = state)

        assertEquals(LadderOutcome.UPSTREAM_DOWN, l.runOne(candidate(dir, "Ep")))
        assertTrue(Files.notExists(dir.resolve("Ep.en.srt")))
        // UPSTREAM_DOWN must not consume a retry attempt: a Bazarr/Whisper
        // outage must never exile a recoverable episode into backoff.
        assertEquals(1L, state.getValue(25749L).attempts)
    }

    @Test
    fun never_clobbers_a_sidecar_that_appeared_mid_run() = runBlocking {
        val dir = Files.createTempDirectory("ladder-race")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        val state = mutableMapOf(25749L to SavedState("R2_BAZARR", 1L, null))

        val l = ladder(
            hasEmbedded = { false },
            // Bazarr lands a sidecar while whisper is still running.
            whisper = { _, _ ->
                Files.writeString(dir.resolve("Ep.en.srt"), "FROM BAZARR\n")
                "FROM WHISPER\n"
            },
            state = state,
        )

        l.runOne(candidate(dir, "Ep"))
        assertEquals("FROM BAZARR\n", Files.readString(dir.resolve("Ep.en.srt")))

        val leftoverTmp = Files.list(dir).use { s -> s.toList() }
            .filter { it.fileName.toString().endsWith(".tmp") }
        assertTrue(leftoverTmp.isEmpty(), "leftover temp files: $leftoverTmp")
    }

    @Test
    fun sweep_does_nothing_while_plex_is_streaming() = runBlocking {
        val dir = Files.createTempDirectory("ladder-gate-plex")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        val state = mutableMapOf<Long, SavedState>()

        val l = ladder(
            candidates = { listOf(candidate(dir, "Ep")) },
            plexSessions = { 1 },
            hasEmbedded = { error("gate must stop the sweep") },
            bazarr = { _, _ -> error("gate must stop the sweep") },
            whisper = { _, _ -> error("gate must stop the sweep") },
            state = state,
        )
        val report = l.sweep()
        assertEquals(0, report.considered)
        assertTrue(report.skippedGate > 0)
        assertTrue(state.isEmpty(), "gate-closed sweep must not persist any state")
    }

    @Test
    fun gate_is_re_evaluated_before_every_candidate_and_aborts_the_batch() = runBlocking {
        // The gate is what keeps Whisper off the CPU while Plex streams.
        // A single R3 candidate runs for tens of minutes, so a gate decided
        // once at the top of the sweep is worthless by the second candidate:
        // a stream starting mid-sweep must stop the remaining work.
        val dir = Files.createTempDirectory("ladder-gate-midsweep")
        Files.writeString(dir.resolve("A.mkv"), "x")
        Files.writeString(dir.resolve("A.en.srt"), "1\n")
        Files.writeString(dir.resolve("B.mkv"), "x")
        Files.writeString(dir.resolve("B.en.srt"), "1\n")

        // Idle for the opening gate and the first candidate, streaming by
        // the time the second candidate is up.
        val probes = java.util.concurrent.atomic.AtomicInteger(0)
        val state = mutableMapOf<Long, SavedState>()
        val l = ladder(
            candidates = {
                listOf(
                    candidate(dir, "A").copy(episodeId = 1L),
                    candidate(dir, "B").copy(episodeId = 2L),
                )
            },
            plexSessions = { if (probes.getAndIncrement() < 2) 0 else 1 },
            state = state,
        )

        val report = l.sweep()

        assertEquals(1, report.considered, "second candidate must not have run")
        assertTrue(report.gateAborted, "mid-sweep gate closure must be recorded")
        assertEquals("plex streaming (1)", report.gateReason)
        assertTrue(state.containsKey(1L), "the first candidate still ran")
        assertTrue(!state.containsKey(2L), "the dropped candidate must not be recorded")
    }

    @Test
    fun a_transient_outage_still_costs_nothing_but_a_sustained_one_escalates() = runBlocking {
        val dir = Files.createTempDirectory("ladder-upstream-escalate")
        Files.writeString(dir.resolve("Ep.mkv"), "x")

        // First failure: flat 1-hour retry, no attempt consumed.
        val state = mutableMapOf(25749L to SavedState("R2_BAZARR", 1L, null, 0L))
        val l = ladder(hasEmbedded = { false }, whisper = { _, _ -> null }, state = state)

        assertEquals(LadderOutcome.UPSTREAM_DOWN, l.runOne(candidate(dir, "Ep")))
        var saved = state.getValue(25749L)
        assertEquals(1L, saved.attempts, "a transient outage must not consume the retry budget")
        assertEquals(1L, saved.upstreamDowns)
        assertTrue(hoursFromNow(saved.nextRetryAt) < 2.0, "expected the 1h outage retry")

        // Nth consecutive failure: the outage is no longer plausibly
        // transient, so the episode joins the normal backoff curve instead
        // of being retried hourly forever.
        state[25749L] = SavedState("R2_BAZARR", 1L, null, (UPSTREAM_DOWN_ESCALATE_AFTER - 1).toLong())
        assertEquals(LadderOutcome.UPSTREAM_DOWN, l.runOne(candidate(dir, "Ep")))
        saved = state.getValue(25749L)
        assertEquals(2L, saved.attempts, "an escalated outage consumes an attempt")
        assertEquals(UPSTREAM_DOWN_ESCALATE_AFTER.toLong(), saved.upstreamDowns)
        assertTrue(hoursFromNow(saved.nextRetryAt) > 24.0, "expected the backoff curve, got the 1h retry")
    }

    @Test
    fun a_recovery_resets_the_outage_streak() = runBlocking {
        val dir = Files.createTempDirectory("ladder-upstream-reset")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        Files.writeString(dir.resolve("Ep.en.srt"), "1\n")

        val state = mutableMapOf(25749L to SavedState("R3_WHISPER", 1L, null, 4L))
        val l = ladder(state = state)

        assertEquals(LadderOutcome.SATISFIED, l.runOne(candidate(dir, "Ep")))
        assertEquals(0L, state.getValue(25749L).upstreamDowns)
    }

    @Test
    fun an_unextractable_embedded_track_does_not_trap_the_episode_on_r1() = runBlocking {
        val dir = Files.createTempDirectory("ladder-r1-trap")
        Files.writeString(dir.resolve("Ep.mkv"), "x")

        // The file HAS an embedded English text track, but extraction always
        // fails. Without an r1AlreadyTried flag the episode picks R1 forever.
        val state = mutableMapOf<Long, SavedState>()
        var extractCalls = 0
        var bazarrCalls = 0
        val l = ladder(
            hasEmbedded = { true },
            extractEmbedded = { extractCalls++; false },
            bazarr = { _, _ -> bazarrCalls++; true },
            state = state,
        )

        assertEquals(LadderOutcome.NO_SOURCE, l.runOne(candidate(dir, "Ep")))
        assertEquals("R1_EMBEDDED", state.getValue(25749L).rung)

        // Second pass: R1 has had its turn, so the ladder moves on.
        assertEquals(LadderOutcome.NO_SOURCE, l.runOne(candidate(dir, "Ep")))
        assertEquals("R2_BAZARR", state.getValue(25749L).rung)

        // Third pass: still must not fall back to R1 (which would oscillate
        // R1 <-> R2 forever and never reach whisper).
        l.runOne(candidate(dir, "Ep"))
        assertEquals("R3_WHISPER", state.getValue(25749L).rung)
        assertEquals(1, extractCalls, "R1 must be attempted exactly once")
        assertEquals(1, bazarrCalls)
    }

    @Test
    fun a_probe_failure_is_unreadable_with_a_long_backoff_not_an_outage() = runBlocking {
        val dir = Files.createTempDirectory("ladder-unreadable")
        Files.writeString(dir.resolve("Ep.mkv"), "x")

        val state = mutableMapOf<Long, SavedState>()
        val l = ladder(
            hasEmbedded = { throw FfmpegProbeException("ffprobe exit 1") },
            bazarr = { _, _ -> error("an unreadable file must not reach bazarr") },
            whisper = { _, _ -> error("an unreadable file must not reach whisper") },
            state = state,
        )

        assertEquals(LadderOutcome.UNREADABLE, l.runOne(candidate(dir, "Ep")))
        val saved = state.getValue(25749L)
        assertEquals(1L, saved.attempts, "UNREADABLE consumes an attempt; UPSTREAM_DOWN would not")
        assertTrue(hoursFromNow(saved.nextRetryAt) > 12.0, "expected the backoff curve, got the 1h outage retry")
    }

    @Test
    fun a_cancellation_aborts_the_sweep_instead_of_being_swallowed() = runBlocking {
        val dir = Files.createTempDirectory("ladder-cancel")
        Files.writeString(dir.resolve("A.mkv"), "x")
        Files.writeString(dir.resolve("B.mkv"), "x")

        var probed = 0
        val l = ladder(
            candidates = {
                listOf(
                    candidate(dir, "A").copy(episodeId = 1L),
                    candidate(dir, "B").copy(episodeId = 2L),
                )
            },
            hasEmbedded = {
                probed++
                throw kotlinx.coroutines.CancellationException("shutting down")
            },
        )

        var thrown: Throwable? = null
        try {
            l.sweep()
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }
        assertTrue(thrown != null, "cancellation must propagate out of the sweep")
        assertEquals(1, probed, "the sweep must stop at the cancelled candidate")
    }

    @Test
    fun sweep_does_nothing_while_sonarr_is_congested() = runBlocking {
        val dir = Files.createTempDirectory("ladder-gate-congested")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        val state = mutableMapOf<Long, SavedState>()

        val l = ladder(
            candidates = { listOf(candidate(dir, "Ep")) },
            congested = { true },
            hasEmbedded = { error("gate must stop the sweep") },
            bazarr = { _, _ -> error("gate must stop the sweep") },
            whisper = { _, _ -> error("gate must stop the sweep") },
            state = state,
        )
        val report = l.sweep()
        assertEquals(0, report.considered)
        assertTrue(report.skippedGate > 0)
        assertTrue(state.isEmpty(), "gate-closed sweep must not persist any state")
    }
}
