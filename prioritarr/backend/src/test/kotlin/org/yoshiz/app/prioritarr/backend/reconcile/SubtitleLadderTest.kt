package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        extractEmbedded: suspend (Path) -> Boolean = { false },
        hasEmbedded: suspend (Path) -> Boolean = { false },
        bazarr: suspend (Long, Long) -> Boolean = { _, _ -> false },
        whisper: suspend (Path, String?) -> String? = { _, _ -> null },
        plexSessions: suspend () -> Int? = { 0 },
        congested: suspend () -> Boolean = { false },
        whisperEnabled: Boolean = true,
        state: MutableMap<Long, Pair<String, Long>> = mutableMapOf(),
    ) = SubtitleLadder(
        candidates = { emptyList() },
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
        loadState = { id -> state[id]?.let { LadderState(it.first, it.second) } },
        saveState = { id, rung, outcome, attempts, _ -> state[id] = rung to attempts },
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
        val state = mutableMapOf(25749L to ("R2_BAZARR" to 1L))
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
        val state = mutableMapOf(25749L to ("R2_BAZARR" to 1L))

        val l = ladder(hasEmbedded = { false }, whisper = { _, _ -> null }, state = state)

        assertEquals(LadderOutcome.UPSTREAM_DOWN, l.runOne(candidate(dir, "Ep")))
        assertTrue(Files.notExists(dir.resolve("Ep.en.srt")))
    }

    @Test
    fun never_clobbers_a_sidecar_that_appeared_mid_run() = runBlocking {
        val dir = Files.createTempDirectory("ladder-race")
        Files.writeString(dir.resolve("Ep.mkv"), "x")
        val state = mutableMapOf(25749L to ("R2_BAZARR" to 1L))

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
    }

    @Test
    fun sweep_does_nothing_while_plex_is_streaming() = runBlocking {
        val l = ladder(plexSessions = { 1 }, hasEmbedded = { error("gate must stop the sweep") })
        val report = l.sweep()
        assertEquals(0, report.considered)
        assertTrue(report.skippedGate > 0)
    }

    @Test
    fun sweep_does_nothing_while_sonarr_is_congested() = runBlocking {
        val l = ladder(congested = { true }, hasEmbedded = { error("gate must stop the sweep") })
        val report = l.sweep()
        assertEquals(0, report.considered)
    }
}
