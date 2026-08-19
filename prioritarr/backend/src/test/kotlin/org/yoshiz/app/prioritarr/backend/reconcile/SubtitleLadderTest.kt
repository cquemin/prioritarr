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
private data class SavedState(val rung: String, val attempts: Long, val nextRetryAt: String?)

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
        loadState = { id -> state[id]?.let { LadderState(it.rung, it.attempts) } },
        saveState = { id, rung, _, attempts, nextRetryAt -> state[id] = SavedState(rung, attempts, nextRetryAt) },
    )

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
