# English SRT Ladder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee every anime episode ends with an external `.en.srt` sidecar, via an ordered fallback ladder that climbs from free local extraction to Whisper transcription-translation.

**Architecture:** One new `sub-ladder` scheduler job owns an explicit per-episode state machine with four rungs (R0 satisfied check → R1 embedded extract → R2 Bazarr provider search → R3 Whisper). The decision core is pure functions; all I/O (ffprobe, ffmpeg, Bazarr, Whisper, Plex, Sonarr) is seam-injected so it unit-tests without those services. Backfill yields to live Plex playback and in-flight P1/P2 Sonarr searches.

**Tech Stack:** Kotlin, Ktor client, SQLDelight, kotlin.test, Gradle.

## Global Constraints

- Satisfied means **exactly** `<base>.en.srt`. `.en.hi.srt` and bare `.srt` are `VARIANT_ONLY`; `.en.forced.srt` alone does **not** count as satisfied.
- `VARIANT_ONLY` blocks R2 and R3 but **not** R1.
- **No Bazarr configuration changes.** `adaptive_searching` stays enabled; `use_whisper_fallback` stays `false`.
- Gates **fail closed**: a Plex probe error means busy, not idle.
- `UPSTREAM_DOWN` must **not** consume an attempt from the backoff budget.
- An existing `.en.srt` is never overwritten; re-check existence immediately before the atomic move.
- Whisper is **globally serialised** — one episode at a time, never concurrent.
- Temp files use **unique** names, never the deterministic `base.lang.srt.tmp` (it already races).
- **All timestamps written to or compared against the DB use `Database.ISO_OFFSET`** (or `Database.nowIsoOffset()`), never `OffsetDateTime.toString()` / `Instant.toString()`. `next_retry_at` is compared as a string in SQL; the JDK prints `Z` for zero offset while the codebase pins `+00:00`, and `'Z'` sorts above `'+'`, so a mismatched row never comes due.
- Run tests with `.\gradlew.bat :backend:test --tests "<pattern>"` from `D:\git\prioritarr\prioritarr`.
- Backend source root: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/`
- Test root: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/`

## File Structure

| File | Responsibility |
|---|---|
| `reconcile/SubtitleLadderModel.kt` (new) | Pure decision core: sidecar classification, rung selection, backoff, gate. No I/O. |
| `reconcile/SubtitleLadder.kt` (new) | Orchestrator: walks candidates, climbs rungs, records state. All I/O via seams. |
| `clients/Whisper.kt` (new) | `WhisperClient.translateToSrt` — multipart POST to `/asr`. |
| `reconcile/FfmpegAudioIo.kt` (new) | Real ffmpeg audio extraction seam. |
| `reconcile/SubtitleExtractor.kt` (modify) | `matchesLang` title fallback; `hasSidecar` → classifier. |
| `database/Database.kt` (modify) | `subtitle_ladder_state` accessors. |
| `sqldelight/.../Schema.sq` (modify) | `subtitle_ladder_state` table + queries. |
| `clients/Plex.kt` (modify) | `activeSessionCountOrNull()` for fail-closed gating. |
| `config/Settings.kt` (modify) | Ladder settings + env vars. |
| `Constants.kt` (modify) | `JobId.SUB_LADDER`. |
| `Main.kt` (modify) | Wire `BazarrClient` (currently never instantiated), `WhisperClient`, register the job. |

---

### Task 1: Sidecar classification

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModel.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModelTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `enum class SidecarState { SATISFIED, VARIANT_ONLY, NONE }`, `fun classifySidecars(siblingNames: Set<String>, base: String): SidecarState`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.reconcile

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleLadderModelTest {

    private val base = "Show - S01E01 - Title WEBDL-1080p"

    @Test
    fun exact_en_srt_is_satisfied() {
        assertEquals(
            SidecarState.SATISFIED,
            classifySidecars(setOf("$base.mkv", "$base.en.srt"), base),
        )
    }

    @Test
    fun hi_variant_is_variant_only() {
        assertEquals(
            SidecarState.VARIANT_ONLY,
            classifySidecars(setOf("$base.mkv", "$base.en.hi.srt"), base),
        )
    }

    @Test
    fun bare_srt_is_variant_only() {
        assertEquals(
            SidecarState.VARIANT_ONLY,
            classifySidecars(setOf("$base.mkv", "$base.srt"), base),
        )
    }

    @Test
    fun forced_alone_is_none_not_satisfied() {
        // Forced tracks carry signs/songs only, never dialogue.
        assertEquals(
            SidecarState.NONE,
            classifySidecars(setOf("$base.mkv", "$base.en.forced.srt"), base),
        )
    }

    @Test
    fun exact_wins_over_variant() {
        assertEquals(
            SidecarState.SATISFIED,
            classifySidecars(setOf("$base.en.srt", "$base.en.hi.srt"), base),
        )
    }

    @Test
    fun nothing_is_none() {
        assertEquals(SidecarState.NONE, classifySidecars(setOf("$base.mkv"), base))
    }

    @Test
    fun other_episode_sidecar_does_not_leak() {
        assertEquals(
            SidecarState.NONE,
            classifySidecars(setOf("$base.mkv", "Show - S01E02 - Other.en.srt"), base),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderModelTest*"`
Expected: FAIL — compilation error, `classifySidecars` and `SidecarState` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package org.yoshiz.app.prioritarr.backend.reconcile

/**
 * How well an episode's English subtitles are covered on disk.
 *
 * The bar is deliberately strict: only an exact `<base>.en.srt` counts
 * as done. Plex soft-serves that and direct-plays; anything else either
 * forces a transcode or is not full dialogue.
 */
enum class SidecarState {
    /** Exact `<base>.en.srt` present. Nothing to do. */
    SATISFIED,

    /**
     * A usable English SRT exists under a variant name (`.en.hi.srt` or
     * bare `.srt`). Good enough that we must never spend network or CPU
     * on it, but we still take a free upgrade to a clean `.en.srt`.
     */
    VARIANT_ONLY,

    /** No usable English SRT. Full ladder applies. */
    NONE,
}

/**
 * Classify the English-subtitle coverage for [base] given the set of
 * file names sitting in the same directory.
 *
 * `.en.forced.srt` is deliberately NOT a variant: forced tracks carry
 * signs and songs only, so treating one as coverage would leave an
 * episode with no dialogue subtitles and no further attempts.
 */
fun classifySidecars(siblingNames: Set<String>, base: String): SidecarState = when {
    "$base.en.srt" in siblingNames -> SidecarState.SATISFIED
    "$base.en.hi.srt" in siblingNames -> SidecarState.VARIANT_ONLY
    "$base.srt" in siblingNames -> SidecarState.VARIANT_ONLY
    else -> SidecarState.NONE
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderModelTest*"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModel.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModelTest.kt
git commit -m "feat(sub-ladder): sidecar classification with strict .en.srt bar"
```

---

### Task 2: Make `SubtitleExtractor` honour the strict bar

Three changes to the same file, all serving R1. Independently shippable — step 3 alone recovers untagged-English files like Super Dragon Ball Heroes S06E01/E02, whose tracks are English but carry `title=English` with no language tag.

**Critical:** the `hasSidecar` narrowing is not cosmetic. `extractForFile` skips whenever `hasSidecar` is true, and `hasSidecar` currently counts `.en.hi.srt` as coverage. Without this change the ladder's "variant still allows the free rung" rule silently does nothing, because the extractor refuses to run.

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleExtractor.kt:244-263`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/FfmpegSubtitleIo.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleExtractorTest.kt` (existing file, add tests)

**Interfaces:**
- Consumes: existing `SubStream(index, codecName, language, title, default, forced)`
- Produces: no public signature change — `matchesLang` and `hasSidecar` stay private. Adds `FfmpegSubtitleIo.convertSubtitleFile(src: Path, target: Path): Boolean`.

- [ ] **Step 1: Write the failing test**

Append to the existing `SubtitleExtractorTest` class:

```kotlin
    @Test
    fun untagged_track_titled_english_is_extracted() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-ladder-untagged")
        val video = dir.resolve("Show - S06E01 - TBA HDTV-1080p.mkv")
        java.nio.file.Files.writeString(video, "x")

        var extractedIndex: Int? = null
        val extractor = SubtitleExtractor(
            paths = { listOf(dir.toString()) },
            langs = { listOf("en") },
            maxPerRun = { 10 },
            // No language tag at all — only a title. This is what the
            // Super Dragon Ball Heroes S06E01/E02 muxes look like.
            probe = { listOf(SubStream(index = 0, codecName = "ass", language = null, title = "English")) },
            extract = { _, idx, target ->
                extractedIndex = idx
                java.nio.file.Files.writeString(target, "1\n00:00:01,000 --> 00:00:02,000\nhi\n")
                true
            },
        )

        val report = extractor.sweep()

        assertEquals(1, report.extracted, "untagged English track must be extracted")
        assertEquals(0, extractedIndex)
        assertTrue(java.nio.file.Files.exists(dir.resolve("Show - S06E01 - TBA HDTV-1080p.en.srt")))
    }

    @Test
    fun language_tag_wins_over_a_misleading_title() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-ladder-tagwins")
        val video = dir.resolve("Show - S06E03 - Polish HDTV-1080p.mkv")
        java.nio.file.Files.writeString(video, "x")

        val extractor = SubtitleExtractor(
            paths = { listOf(dir.toString()) },
            langs = { listOf("en") },
            maxPerRun = { 10 },
            // Tagged Polish. The title must not rescue it — this is the
            // SDBH S06E03 "Grupa Mirai" case that started this work.
            probe = { listOf(SubStream(index = 0, codecName = "ass", language = "pol", title = "English fansub")) },
            extract = { _, _, _ -> error("must not extract a tagged non-English track") },
        )

        val report = extractor.sweep()

        assertEquals(0, report.extracted)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleExtractorTest*"`
Expected: `untagged_track_titled_english_is_extracted` FAILS with `expected:<1> but was:<0>` — `matchesLang` returns false on a null language. The second test passes already.

- [ ] **Step 3: Write minimal implementation**

Replace `matchesLang` at `SubtitleExtractor.kt:259-263`:

```kotlin
    /**
     * Does this stream carry [lang2]?
     *
     * The language tag wins whenever it is present — a tagged `pol`
     * track titled "English fansub" is Polish, and treating it as
     * English is exactly the bug that produced Polish subtitles on
     * Super Dragon Ball Heroes S06E03.
     *
     * Only when the tag is absent do we fall back to the track title.
     * Some muxes set no language at all and label the track "English";
     * without this fallback those files are invisible to the extractor
     * and fall through every rung of the ladder.
     */
    private fun matchesLang(s: SubStream, lang2: String): Boolean {
        val aliases = LANG_ALIASES[lang2] ?: setOf(lang2)
        val lang = s.language?.lowercase()?.trim()
        if (!lang.isNullOrEmpty() && lang != "und") return lang in aliases
        val title = s.title?.lowercase()?.trim() ?: return false
        return aliases.any { alias -> title == alias || title.contains(alias) }
    }
```

Note: `und` (ISO "undetermined") is treated as absent — it carries no information, so the title fallback should apply.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleExtractorTest*"`
Expected: PASS, all existing tests plus the two new ones.

- [ ] **Step 5: Write the failing test for the strict-bar narrowing**

```kotlin
    @Test
    fun hi_variant_no_longer_blocks_extraction() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-strict-bar")
        val video = dir.resolve("Show - S06E06 - Ep WEBDL-1080p.mkv")
        java.nio.file.Files.writeString(video, "x")
        // Bazarr previously landed a hearing-impaired sidecar. That is
        // NOT the strict bar, so a free extraction must still run.
        java.nio.file.Files.writeString(dir.resolve("Show - S06E06 - Ep WEBDL-1080p.en.hi.srt"), "1\n")

        val extractor = SubtitleExtractor(
            paths = { listOf(dir.toString()) },
            langs = { listOf("en") },
            maxPerRun = { 10 },
            probe = { listOf(SubStream(index = 0, codecName = "ass", language = "eng")) },
            extract = { _, _, target ->
                java.nio.file.Files.writeString(target, "1\n00:00:01,000 --> 00:00:02,000\nhi\n")
                true
            },
        )

        val report = extractor.sweep()

        assertEquals(1, report.extracted, ".en.hi.srt must not count as satisfied")
        assertTrue(java.nio.file.Files.exists(dir.resolve("Show - S06E06 - Ep WEBDL-1080p.en.srt")))
    }

    @Test
    fun exact_en_srt_still_blocks_extraction() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-strict-bar-sat")
        java.nio.file.Files.writeString(dir.resolve("Ep.mkv"), "x")
        java.nio.file.Files.writeString(dir.resolve("Ep.en.srt"), "1\n")

        val extractor = SubtitleExtractor(
            paths = { listOf(dir.toString()) },
            langs = { listOf("en") },
            maxPerRun = { 10 },
            probe = { listOf(SubStream(index = 0, codecName = "ass", language = "eng")) },
            extract = { _, _, _ -> error("must never clobber an existing .en.srt") },
        )

        assertEquals(0, extractor.sweep().extracted)
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleExtractorTest*"`
Expected: `hi_variant_no_longer_blocks_extraction` FAILS with `expected:<1> but was:<0>`.

- [ ] **Step 7: Narrow `hasSidecar` to the strict bar**

Replace `hasSidecar` at `SubtitleExtractor.kt:244-254`:

```kotlin
    /**
     * Is the strict bar already met for [lang2]?
     *
     * Only an exact `<base>.<lang2>.srt` counts. `.hi` / `.forced` /
     * bare `.srt` deliberately do NOT: a hearing-impaired or
     * signs-only sidecar is not the clean dialogue track we want Plex
     * to soft-serve, and treating one as coverage would permanently
     * block the free extraction that could produce the real thing.
     *
     * Narrower than it used to be — that is the point. We still never
     * overwrite the file named here, so Bazarr's downloads remain safe.
     */
    private fun hasSidecar(file: Path, lang2: String): Boolean {
        val dir = file.parent ?: return false
        return Files.exists(dir.resolve("${baseName(file)}.$lang2.srt"))
    }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleExtractorTest*"`
Expected: PASS.

Note: `skippedHasSidecar` counts will drop across the library after this. That is expected — those files were never actually satisfied.

- [ ] **Step 9: Write the failing test for ASS sidecar conversion**

```kotlin
    @Test
    fun standalone_ass_sidecar_is_converted_to_srt() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-ass-convert")
        java.nio.file.Files.writeString(dir.resolve("Ep.mkv"), "x")
        java.nio.file.Files.writeString(dir.resolve("Ep.en.ass"), "[Script Info]\n")

        var converted: Pair<String, String>? = null
        val extractor = SubtitleExtractor(
            paths = { listOf(dir.toString()) },
            langs = { listOf("en") },
            maxPerRun = { 10 },
            // No embedded subtitle streams at all.
            probe = { emptyList() },
            extract = { _, _, _ -> error("no embedded track to extract") },
            convertSidecar = { src, target ->
                converted = src.fileName.toString() to target.fileName.toString()
                java.nio.file.Files.writeString(target, "1\n00:00:01,000 --> 00:00:02,000\nhi\n")
                true
            },
        )

        val report = extractor.sweep()

        assertEquals(1, report.extracted)
        assertEquals("Ep.en.ass" to "Ep.en.srt", converted)
    }
```

- [ ] **Step 10: Run it to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleExtractorTest*"`
Expected: FAIL — no `convertSidecar` parameter.

- [ ] **Step 11: Add the conversion seam and rung**

Add a constructor parameter to `SubtitleExtractor`, after `extract`:

```kotlin
    /**
     * ffmpeg seam converting a standalone subtitle FILE (not an embedded
     * stream) to SRT. Used when a `.ass` sidecar exists but no `.srt` —
     * free, and Plex can only soft-serve the SRT.
     */
    private val convertSidecar: suspend (src: Path, target: Path) -> Boolean = { _, _ -> false },
```

In `extractInto`, before probing for embedded streams, try the sidecar conversion:

```kotlin
        // Free upgrade: a standalone .ass sidecar converts to .srt with no
        // decode of the video at all. Try it before touching ffprobe.
        for (cand in listOf("$base.$lang2.ass", "$base.ass")) {
            val src = file.parent?.resolve(cand) ?: continue
            if (!Files.exists(src)) continue
            val target = sidecarTarget(file, lang2)
            val tmp = file.parent.resolve("${baseName(file)}.$lang2.srt.${java.util.UUID.randomUUID()}.tmp")
            if (convertSidecar(src, tmp)) {
                if (!Files.exists(target)) {
                    atomicMove(tmp, target)
                    report.extracted++
                    return
                }
            }
            deleteQuiet(tmp)
        }
```

Bind `val base = baseName(file)` at the top of `extractInto` if not already present.

Add the real implementation to `FfmpegSubtitleIo`:

```kotlin
    /**
     * Convert a standalone subtitle file to SRT. `-f srt` is mandatory:
     * the temp target ends `.tmp`, so ffmpeg cannot infer the format and
     * fails with "Unable to choose an output format".
     */
    suspend fun convertSubtitleFile(src: Path, target: Path): Boolean = withContext(Dispatchers.IO) {
        val cmd = listOf("ffmpeg", "-v", "error", "-y", "-i", src.toString(), "-f", "srt", target.toString())
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.MINUTES)) { p.destroyForcibly(); return@withContext false }
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
```

- [ ] **Step 12: Run tests to verify they pass**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleExtractorTest*"`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleExtractor.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/FfmpegSubtitleIo.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleExtractorTest.kt
git commit -m "fix(sub-extract): strict .en.srt bar, title fallback, and ASS sidecar conversion"
```

---

### Task 3: Rung selection, outcomes, and backoff

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModel.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModelTest.kt`

**Interfaces:**
- Consumes: `SidecarState` (Task 1)
- Produces: `enum class Rung`, `enum class LadderOutcome`, `fun nextRung(...): Rung`, `fun backoffFor(attempts: Int): Duration`, `fun consumesAttempt(outcome: LadderOutcome): Boolean`

- [ ] **Step 1: Write the failing test**

Append to `SubtitleLadderModelTest`:

```kotlin
    @Test
    fun satisfied_short_circuits() {
        assertEquals(
            Rung.R0_SATISFIED,
            nextRung(SidecarState.SATISFIED, hasEmbeddedEnglishText = true, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun embedded_text_track_is_tried_first() {
        assertEquals(
            Rung.R1_EMBEDDED,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = true, priority = 5,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun variant_still_allows_the_free_rung() {
        assertEquals(
            Rung.R1_EMBEDDED,
            nextRung(SidecarState.VARIANT_ONLY, hasEmbeddedEnglishText = true, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun variant_blocks_the_expensive_rungs() {
        // No embedded track to extract, so R1 is unavailable. A P1 series
        // would normally reach Whisper — the variant must stop it.
        assertEquals(
            Rung.R4_EXHAUSTED,
            nextRung(SidecarState.VARIANT_ONLY, hasEmbeddedEnglishText = false, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun no_embedded_track_falls_to_bazarr() {
        assertEquals(
            Rung.R2_BAZARR,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 5,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun whisper_only_within_the_priority_gate() {
        // P1 is inside the gate.
        assertEquals(
            Rung.R3_WHISPER,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true, bazarrAlreadyTried = true),
        )
        // P3 is outside it.
        assertEquals(
            Rung.R4_EXHAUSTED,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 3,
                whisperMaxPriority = 2, whisperEnabled = true, bazarrAlreadyTried = true),
        )
    }

    @Test
    fun whisper_master_switch_off_exhausts_instead() {
        assertEquals(
            Rung.R4_EXHAUSTED,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = false, bazarrAlreadyTried = true),
        )
    }

    @Test
    fun backoff_escalates_then_caps_at_four_weeks() {
        assertEquals(java.time.Duration.ofDays(1), backoffFor(1))
        assertEquals(java.time.Duration.ofDays(3), backoffFor(2))
        assertEquals(java.time.Duration.ofDays(7), backoffFor(3))
        assertEquals(java.time.Duration.ofDays(28), backoffFor(4))
        assertEquals(java.time.Duration.ofDays(28), backoffFor(99), "must cap")
    }

    @Test
    fun upstream_down_does_not_consume_an_attempt() {
        // A Bazarr outage must never push an episode toward 4-week backoff.
        assertEquals(false, consumesAttempt(LadderOutcome.UPSTREAM_DOWN))
        assertEquals(true, consumesAttempt(LadderOutcome.NO_SOURCE))
        assertEquals(true, consumesAttempt(LadderOutcome.UNREADABLE))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderModelTest*"`
Expected: FAIL — `Rung`, `LadderOutcome`, `nextRung`, `backoffFor`, `consumesAttempt` unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `SubtitleLadderModel.kt`:

```kotlin
import java.time.Duration

/** Where an episode is in the ladder. Ordered cheapest-first. */
enum class Rung {
    /** Nothing to do. */
    R0_SATISFIED,

    /** Extract an embedded English text track locally. Free. */
    R1_EMBEDDED,

    /** Ask Bazarr to search its providers. Network only, no CPU. */
    R2_BAZARR,

    /** Whisper transcribe-translate from the audio. Expensive, gated. */
    R3_WHISPER,

    /** Every applicable rung has been tried. */
    R4_EXHAUSTED,
}

/** Terminal result of one ladder attempt on one episode. */
enum class LadderOutcome {
    SATISFIED,

    /** Every applicable rung ran and found nothing. */
    NO_SOURCE,

    /** Bazarr or Whisper was unreachable/timed out. Our fault, not the file's. */
    UPSTREAM_DOWN,

    /** ffprobe/ffmpeg could not read the file. */
    UNREADABLE,

    /** A variant sidecar blocks the expensive rungs. */
    BLOCKED_VARIANT,
}

/**
 * Pick the next rung to attempt.
 *
 * [bazarrAlreadyTried] is carried in `subtitle_ladder_state`: without it
 * an episode would ping Bazarr forever and never reach Whisper.
 *
 * The variant rule is the expensive-work guard. 724 files in this
 * library hold a usable `.en.hi.srt` or bare `.srt`; sending them to
 * Whisper would burn roughly 120 hours of CPU producing output worse
 * than the subtitle already on disk.
 */
fun nextRung(
    sidecar: SidecarState,
    hasEmbeddedEnglishText: Boolean,
    priority: Int,
    whisperMaxPriority: Int,
    whisperEnabled: Boolean,
    bazarrAlreadyTried: Boolean = false,
): Rung {
    if (sidecar == SidecarState.SATISFIED) return Rung.R0_SATISFIED
    // R1 is free, so it runs even when a variant is present.
    if (hasEmbeddedEnglishText) return Rung.R1_EMBEDDED
    if (sidecar == SidecarState.VARIANT_ONLY) return Rung.R4_EXHAUSTED
    if (!bazarrAlreadyTried) return Rung.R2_BAZARR
    if (whisperEnabled && priority <= whisperMaxPriority) return Rung.R3_WHISPER
    return Rung.R4_EXHAUSTED
}

/** Retry schedule after a failed attempt: 1d, 3d, 1w, then 4w forever. */
fun backoffFor(attempts: Int): Duration = when {
    attempts <= 1 -> Duration.ofDays(1)
    attempts == 2 -> Duration.ofDays(3)
    attempts == 3 -> Duration.ofDays(7)
    else -> Duration.ofDays(28)
}

/**
 * Should this outcome push the episode further down the backoff curve?
 *
 * Infrastructure failures must not: a Bazarr restart during a sweep
 * would otherwise exile a perfectly recoverable episode for four weeks.
 */
fun consumesAttempt(outcome: LadderOutcome): Boolean = when (outcome) {
    LadderOutcome.NO_SOURCE, LadderOutcome.UNREADABLE -> true
    LadderOutcome.UPSTREAM_DOWN, LadderOutcome.SATISFIED, LadderOutcome.BLOCKED_VARIANT -> false
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderModelTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModel.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModelTest.kt
git commit -m "feat(sub-ladder): rung selection, outcomes, and backoff schedule"
```

---

### Task 4: Fail-closed gate

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModel.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Plex.kt` (add `activeSessionCountOrNull`)
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModelTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: `data class LadderGate(val open: Boolean, val reason: String, val idleTicks: Int)`, `fun decideLadderGate(plexSessions: Int?, congested: Boolean, idleTicks: Int, resumeAfterIdleTicks: Int): LadderGate`, `PlexClient.activeSessionCountOrNull(): Int?`

- [ ] **Step 1: Write the failing test**

Append to `SubtitleLadderModelTest`:

```kotlin
    @Test
    fun plex_streaming_closes_the_gate() {
        val g = decideLadderGate(plexSessions = 1, congested = false, idleTicks = 5, resumeAfterIdleTicks = 3)
        assertEquals(false, g.open)
        assertEquals(0, g.idleTicks, "a live session resets the idle counter")
    }

    @Test
    fun plex_probe_error_means_busy_not_idle() {
        // Deliberately unlike decideTdarrPause, which treats an error as 0
        // sessions. For a CPU-bound rung a false "idle" during a stream is
        // the expensive mistake, so null fails CLOSED.
        val g = decideLadderGate(plexSessions = null, congested = false, idleTicks = 99, resumeAfterIdleTicks = 3)
        assertEquals(false, g.open)
        assertEquals(0, g.idleTicks)
    }

    @Test
    fun congestion_closes_the_gate_even_when_plex_is_idle() {
        val g = decideLadderGate(plexSessions = 0, congested = true, idleTicks = 9, resumeAfterIdleTicks = 3)
        assertEquals(false, g.open)
    }

    @Test
    fun gate_opens_only_after_enough_idle_ticks() {
        // Plex reports 0 momentarily mid-playback; debounce before acting.
        val t1 = decideLadderGate(0, congested = false, idleTicks = 0, resumeAfterIdleTicks = 3)
        assertEquals(false, t1.open); assertEquals(1, t1.idleTicks)
        val t2 = decideLadderGate(0, congested = false, idleTicks = t1.idleTicks, resumeAfterIdleTicks = 3)
        assertEquals(false, t2.open); assertEquals(2, t2.idleTicks)
        val t3 = decideLadderGate(0, congested = false, idleTicks = t2.idleTicks, resumeAfterIdleTicks = 3)
        assertEquals(true, t3.open); assertEquals(3, t3.idleTicks)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderModelTest*"`
Expected: FAIL — `decideLadderGate` and `LadderGate` unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `SubtitleLadderModel.kt`:

```kotlin
/** Result of one gate evaluation. [idleTicks] is carried into the next tick. */
data class LadderGate(
    val open: Boolean,
    val reason: String,
    val idleTicks: Int,
)

/**
 * Decide whether the ladder may do work this tick.
 *
 * @param plexSessions active Plex sessions, or **null when the probe
 *   failed**. Null fails CLOSED — unlike [decideTdarrPause], which maps
 *   an error to 0. Running Whisper during a live stream because a probe
 *   blipped is far more costly than skipping one sweep.
 * @param congested true when Sonarr has P1/P2 searches in flight.
 * @param idleTicks consecutive idle polls so far, carried by the caller.
 * @param resumeAfterIdleTicks idle polls required before opening.
 */
fun decideLadderGate(
    plexSessions: Int?,
    congested: Boolean,
    idleTicks: Int,
    resumeAfterIdleTicks: Int,
): LadderGate {
    if (plexSessions == null) return LadderGate(false, "plex probe failed (failing closed)", 0)
    if (plexSessions > 0) return LadderGate(false, "plex streaming ($plexSessions)", 0)
    if (congested) return LadderGate(false, "sonarr search queue congested", idleTicks)
    val newIdle = minOf(idleTicks + 1, resumeAfterIdleTicks)
    return if (newIdle >= resumeAfterIdleTicks) {
        LadderGate(true, "idle", newIdle)
    } else {
        LadderGate(false, "debouncing plex idle ($newIdle/$resumeAfterIdleTicks)", newIdle)
    }
}
```

Add to `PlexClient` in `clients/Plex.kt`, directly below `activeSessionCount()`:

```kotlin
    /**
     * Like [activeSessionCount], but returns **null** when the probe
     * itself failed, rather than collapsing that into 0.
     *
     * The subtitle ladder needs the distinction: 0 means "safe to burn
     * CPU", whereas a failed probe means "unknown", and unknown must
     * behave like busy.
     */
    suspend fun activeSessionCountOrNull(): Int? {
        val doc = try {
            getXml("/status/sessions") ?: return null
        } catch (_: Exception) {
            return null
        }
        doc.getAttribute("size").toIntOrNull()?.let { return it }
        return doc.getElementsByTagName("Video").length + doc.getElementsByTagName("Track").length
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderModelTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModel.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Plex.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderModelTest.kt
git commit -m "feat(sub-ladder): fail-closed Plex/congestion gate"
```

---

### Task 5: `subtitle_ladder_state` persistence

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/SubtitleLadderStateTest.kt`

**Interfaces:**
- Consumes: `LadderOutcome`, `Rung` (Task 3)
- Produces: `Database.getLadderState(episodeId: Long): Subtitle_ladder_state?`, `Database.upsertLadderState(episodeId: Long, lastRung: String, outcome: String, attempts: Long, nextRetryAt: String?)`, `Database.ladderEpisodesDue(now: String, limit: Long): List<Subtitle_ladder_state>`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleLadderStateTest {

    private fun freshDb(): Database {
        val tmp = java.nio.file.Files.createTempFile("prio-ladder-test", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test
    fun round_trips_and_upserts() {
        val db = freshDb()
        assertNull(db.getLadderState(42L))

        db.upsertLadderState(42L, "R2_BAZARR", "NO_SOURCE", 1L, "2026-09-01T00:00:00Z")
        val first = db.getLadderState(42L)!!
        assertEquals("R2_BAZARR", first.last_rung)
        assertEquals(1L, first.attempts)

        db.upsertLadderState(42L, "R3_WHISPER", "SATISFIED", 1L, null)
        val second = db.getLadderState(42L)!!
        assertEquals("R3_WHISPER", second.last_rung)
        assertEquals("SATISFIED", second.outcome)
        assertNull(second.next_retry_at)
    }

    @Test
    fun due_query_excludes_future_retries_and_respects_limit() {
        val db = freshDb()
        db.upsertLadderState(1L, "R2_BAZARR", "NO_SOURCE", 1L, "2026-01-01T00:00:00Z") // past
        db.upsertLadderState(2L, "R2_BAZARR", "NO_SOURCE", 1L, "2099-01-01T00:00:00Z") // future
        db.upsertLadderState(3L, "R2_BAZARR", "NO_SOURCE", 1L, null)                   // never tried again

        val due = db.ladderEpisodesDue(now = "2026-06-01T00:00:00Z", limit = 10L)
        val ids = due.map { it.episode_id }.toSet()

        assertTrue(1L in ids, "past retry time is due")
        assertTrue(3L in ids, "null retry time is due")
        assertTrue(2L !in ids, "future retry time is not due")

        assertEquals(1, db.ladderEpisodesDue("2026-06-01T00:00:00Z", limit = 1L).size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderStateTest*"`
Expected: FAIL — `getLadderState` / `upsertLadderState` / `ladderEpisodesDue` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `Schema.sq`, immediately after the `trakt_id_cache` table block:

```sql
-- Per-episode position in the English-SRT ladder. This is prioritarr's
-- own pacing for the rungs it drives; it does NOT replace Bazarr's
-- adaptive search, which still governs Bazarr's scheduled sweep on a
-- separate code path.
--
-- next_retry_at NULL means "due now" (never attempted, or an attempt
-- that did not schedule a retry).
CREATE TABLE IF NOT EXISTS subtitle_ladder_state (
    episode_id      INTEGER PRIMARY KEY,
    last_rung       TEXT NOT NULL,
    outcome         TEXT NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TEXT,
    next_retry_at   TEXT,
    updated_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_subtitle_ladder_next_retry
    ON subtitle_ladder_state(next_retry_at);
```

And the queries, alongside the other query blocks:

```sql
selectLadderState:
SELECT * FROM subtitle_ladder_state WHERE episode_id = ?;

upsertLadderState:
INSERT INTO subtitle_ladder_state (episode_id, last_rung, outcome, attempts, last_attempt_at, next_retry_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(episode_id) DO UPDATE SET
    last_rung       = excluded.last_rung,
    outcome         = excluded.outcome,
    attempts        = excluded.attempts,
    last_attempt_at = excluded.last_attempt_at,
    next_retry_at   = excluded.next_retry_at,
    updated_at      = excluded.updated_at;

ladderEpisodesDue:
SELECT * FROM subtitle_ladder_state
WHERE next_retry_at IS NULL OR next_retry_at <= ?
ORDER BY next_retry_at IS NOT NULL, next_retry_at
LIMIT ?;
```

Add to `Database.kt`, following the `trakt_id_cache` accessor pattern:

```kotlin
    // ------------------------------------------------------------------
    // subtitle_ladder_state
    // ------------------------------------------------------------------

    fun getLadderState(episodeId: Long): Subtitle_ladder_state? =
        q.selectLadderState(episodeId).executeAsOneOrNull()

    fun upsertLadderState(
        episodeId: Long,
        lastRung: String,
        outcome: String,
        attempts: Long,
        nextRetryAt: String?,
    ) {
        val now = nowIsoOffset()
        q.upsertLadderState(
            episode_id = episodeId,
            last_rung = lastRung,
            outcome = outcome,
            attempts = attempts,
            last_attempt_at = now,
            next_retry_at = nextRetryAt,
            updated_at = now,
        )
    }

    fun ladderEpisodesDue(now: String, limit: Long): List<Subtitle_ladder_state> =
        q.ladderEpisodesDue(now, limit).executeAsList()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderStateTest*"`
Expected: PASS. (SQLDelight regenerates `Subtitle_ladder_state` on build.)

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/sqldelight prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/SubtitleLadderStateTest.kt
git commit -m "feat(sub-ladder): subtitle_ladder_state table and accessors"
```

---

### Task 6: Whisper client

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Whisper.kt`
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/FfmpegAudioIo.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/WhisperClientTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: `class WhisperClient(http: HttpClient, baseUrl: String)` with `suspend fun translateToSrt(wav: Path, sourceLang: String?): String?`; `object FfmpegAudioIo` with `suspend fun extractWav(file: Path, target: Path): Boolean`

The endpoint contract is verified against the running container:
`POST http://whisper:9000/asr?task=translate&language=ja&output=srt`, multipart field name `audio_file`, returns SRT as `text/plain`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperClientTest {

    private fun tempWav(): java.nio.file.Path {
        val p = java.nio.file.Files.createTempFile("whisper-test", ".wav")
        java.nio.file.Files.write(p, ByteArray(64))
        p.toFile().deleteOnExit()
        return p
    }

    @Test
    fun posts_translate_task_and_returns_srt() = runBlocking {
        var seenUrl = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(
                content = "1\n00:00:01,000 --> 00:00:02,000\nHello\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        val srt = client.translateToSrt(tempWav(), sourceLang = "ja")

        assertTrue(srt!!.startsWith("1\n"))
        assertTrue("task=translate" in seenUrl, "must request translation, not transcription: $seenUrl")
        assertTrue("language=ja" in seenUrl, seenUrl)
        assertTrue("output=srt" in seenUrl, seenUrl)
    }

    @Test
    fun omits_language_when_unknown_so_whisper_detects_it() = runBlocking {
        var seenUrl = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond("1\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        client.translateToSrt(tempWav(), sourceLang = null)

        assertTrue("language=" !in seenUrl, "must not send an empty language: $seenUrl")
    }

    @Test
    fun returns_null_on_upstream_error() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        assertNull(client.translateToSrt(tempWav(), sourceLang = "ja"))
    }

    @Test
    fun returns_null_on_empty_body() = runBlocking {
        val engine = MockEngine {
            respond("   ", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        assertNull(client.translateToSrt(tempWav(), sourceLang = "ja"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*WhisperClientTest*"`
Expected: FAIL — `WhisperClient` unresolved.

- [ ] **Step 3: Write minimal implementation**

`clients/Whisper.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Thin client for openai-whisper-asr-webservice (`onerahmet/...`).
 *
 * We use `task=translate`, which takes non-English audio straight to
 * English text in one pass. The two-step alternative (transcribe to
 * Japanese, then translate) doubles CPU and compounds transcription
 * errors through the translator, and the translator in this stack
 * (Lingarr) has never run.
 *
 * This exists because Bazarr cannot be made to do it: its per-episode
 * endpoint calls `generate_subtitles(..., fallback_allowed=False)` with
 * the default and never passes the flag, so no Bazarr configuration can
 * make a per-episode search reach Whisper.
 */
class WhisperClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(WhisperClient::class.java)
    private val root = baseUrl.trimEnd('/')

    /**
     * Translate [wav] to an English SRT document.
     *
     * @param sourceLang ISO-639-1 code of the audio (e.g. "ja"), or null
     *   to let Whisper detect it. Never send an empty value — the
     *   service treats a blank `language` as invalid rather than absent.
     * @return the SRT body, or null on any failure (caller maps that to
     *   UPSTREAM_DOWN, which does not consume a backoff attempt).
     */
    suspend fun translateToSrt(wav: Path, sourceLang: String?): String? = try {
        val langParam = sourceLang?.takeIf { it.isNotBlank() }?.let { "&language=$it" } ?: ""
        val url = "$root/asr?task=translate&output=srt$langParam"
        val bytes = Files.readAllBytes(wav)
        val resp: HttpResponse = http.post(url) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "audio_file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                            },
                        )
                    },
                ),
            )
        }
        if (resp.status.value !in 200..299) {
            logger.warn("whisper: HTTP {} for {}", resp.status.value, wav.fileName)
            null
        } else {
            resp.bodyAsText().takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        logger.warn("whisper: request failed for {}: {}", wav.fileName, e.message)
        null
    }
}
```

`reconcile/FfmpegAudioIo.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Real ffmpeg seam for pulling a Whisper-ready audio track out of a
 * video. Mirrors [FfmpegSubtitleIo]: the process call lives here so the
 * ladder's decision logic stays unit-testable without ffmpeg on the box.
 */
object FfmpegAudioIo {
    private val logger = LoggerFactory.getLogger(FfmpegAudioIo::class.java)

    /**
     * Decode the first audio stream to 16 kHz mono WAV — the format
     * Whisper wants, and far smaller than the source audio.
     */
    suspend fun extractWav(file: Path, target: Path): Boolean = withContext(Dispatchers.IO) {
        val cmd = listOf(
            "ffmpeg", "-v", "error", "-y",
            "-i", file.toString(),
            "-vn", "-ac", "1", "-ar", "16000",
            "-f", "wav", target.toString(),
        )
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                p.destroyForcibly()
                logger.warn("sub-ladder: ffmpeg audio extract timed out for {}", file)
                return@withContext false
            }
            if (p.exitValue() != 0) {
                logger.warn("sub-ladder: ffmpeg audio extract exit {} for {}", p.exitValue(), file)
                return@withContext false
            }
            true
        } catch (e: Exception) {
            logger.warn("sub-ladder: ffmpeg audio extract failed for {}: {}", file, e.message)
            false
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*WhisperClientTest*"`
Expected: PASS, 4 tests.

If `MockEngine` is unavailable, add `testImplementation("io.ktor:ktor-client-mock:$ktorVersion")` to `prioritarr/backend/build.gradle.kts` — check first, as other client tests (`SonarrClientTest`, `TraktClientTest`) already use it.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Whisper.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/FfmpegAudioIo.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/WhisperClientTest.kt
git commit -m "feat(sub-ladder): whisper translate client and ffmpeg audio seam"
```

---

### Task 7: Ladder orchestrator

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadder.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderTest.kt`

**Interfaces:**
- Consumes: `SidecarState`, `classifySidecars`, `Rung`, `LadderOutcome`, `nextRung`, `backoffFor`, `consumesAttempt`, `decideLadderGate`, `LadderGate` (Tasks 1, 3, 4)
- Produces: `data class LadderCandidate(val videoPath: Path, val seriesId: Long, val episodeId: Long, val priority: Int, val audioLang: String?)`, `data class LadderReport(var considered: Int, var satisfied: Int, var extracted: Int, var bazarrTriggered: Int, var whispered: Int, var noSource: Int, var upstreamDown: Int, var skippedGate: Int)`, `class SubtitleLadder(...)` with `suspend fun sweep(): LadderReport` and `suspend fun runOne(candidate: LadderCandidate): LadderOutcome`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderTest*"`
Expected: FAIL — `SubtitleLadder`, `LadderCandidate`, `LadderState` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package org.yoshiz.app.prioritarr.backend.reconcile

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** One episode the ladder may act on. */
data class LadderCandidate(
    val videoPath: Path,
    val seriesId: Long,
    val episodeId: Long,
    val priority: Int,
    /** ISO-639-1 audio language for Whisper, or null to let it detect. */
    val audioLang: String?,
)

/** Persisted position, reduced to what [SubtitleLadder] needs. */
data class LadderState(val lastRung: String, val attempts: Long)

/**
 * Per-sweep aggregate surfaced in the job summary.
 *
 * The rung counters are [AtomicInteger] because [SubtitleLadder.runOne]
 * increments them, and runOne is also called directly by the on-demand
 * endpoint (outside any sweep) — where [SubtitleLadder.lastReport] is
 * null and the increments are simply skipped.
 */
data class LadderReport(
    var considered: Int = 0,
    var satisfied: Int = 0,
    val extracted: AtomicInteger = AtomicInteger(0),
    val bazarrTriggered: AtomicInteger = AtomicInteger(0),
    val whispered: AtomicInteger = AtomicInteger(0),
    var noSource: Int = 0,
    var upstreamDown: Int = 0,
    var skippedGate: Int = 0,
)

/**
 * Walks episodes up an ordered ladder until each has a plain
 * `<base>.en.srt`.
 *
 * Every rung is a seam so the ordering, gating and backoff logic is
 * testable without ffmpeg, Bazarr, Whisper, Plex or Sonarr.
 *
 * Whisper is serialised globally by [whisperSlot]: it is CPU-bound, and
 * two concurrent runs would starve exactly the Plex playback the gate
 * exists to protect.
 */
class SubtitleLadder(
    private val candidates: suspend () -> List<LadderCandidate>,
    private val hasEmbeddedEnglishText: suspend (Path) -> Boolean,
    private val extractEmbedded: suspend (Path) -> Boolean,
    private val triggerBazarr: suspend (seriesId: Long, episodeId: Long) -> Boolean,
    private val whisperTranslate: suspend (Path, String?) -> String?,
    private val plexSessions: suspend () -> Int?,
    private val congested: suspend () -> Boolean,
    private val maxPerSweep: () -> Int,
    private val whisperEnabled: () -> Boolean,
    private val whisperMaxPriority: () -> Int,
    private val resumeAfterIdleTicks: () -> Int,
    private val loadState: (Long) -> LadderState?,
    private val saveState: (episodeId: Long, rung: String, outcome: String, attempts: Long, nextRetryAt: String?) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(SubtitleLadder::class.java)

    /** Carried across ticks to debounce Plex's mid-playback zero reports. */
    private val idleTicks = AtomicInteger(0)

    /** Global Whisper mutex — one transcription at a time, process-wide. */
    private val whisperSlot = kotlinx.coroutines.sync.Mutex()

    /**
     * The in-flight sweep's report, so [runOne] can attribute rung
     * counters. Null when runOne is called directly by the on-demand
     * endpoint.
     */
    @Volatile
    private var lastReport: LadderReport? = null

    suspend fun sweep(): LadderReport {
        val report = LadderReport()
        lastReport = report

        val gate = decideLadderGate(
            plexSessions = try { plexSessions() } catch (_: Exception) { null },
            congested = try { congested() } catch (_: Exception) { false },
            idleTicks = idleTicks.get(),
            resumeAfterIdleTicks = resumeAfterIdleTicks(),
        )
        idleTicks.set(gate.idleTicks)
        if (!gate.open) {
            report.skippedGate = 1
            logger.debug("sub-ladder: standing down — {}", gate.reason)
            return report
        }

        val batch = candidates().take(maxPerSweep().coerceAtLeast(0))
        for (c in batch) {
            report.considered++
            when (runOne(c)) {
                LadderOutcome.SATISFIED -> report.satisfied++
                LadderOutcome.NO_SOURCE -> report.noSource++
                LadderOutcome.UPSTREAM_DOWN -> report.upstreamDown++
                LadderOutcome.UNREADABLE -> report.noSource++
                LadderOutcome.BLOCKED_VARIANT -> {}
            }
        }
        logger.info(
            "sub-ladder: considered={} satisfied={} extracted={} bazarr={} whisper={} noSource={} upstreamDown={}",
            report.considered, report.satisfied, report.extracted.get(),
            report.bazarrTriggered.get(), report.whispered.get(), report.noSource, report.upstreamDown,
        )
        lastReport = null
        return report
    }

    /**
     * Climb one episode by exactly one rung and record the outcome.
     *
     * One rung per call on purpose: R2 is asynchronous inside Bazarr, so
     * the result only exists on a later sweep. Persisting `lastRung` is
     * what lets the next pass know Bazarr has already had its turn and
     * move on to Whisper.
     */
    suspend fun runOne(candidate: LadderCandidate): LadderOutcome {
        val file = candidate.videoPath
        val dir = file.parent ?: return record(candidate, Rung.R4_EXHAUSTED, LadderOutcome.UNREADABLE)
        val base = baseNameOf(file)
        val sidecar = classifySidecars(siblingNames(dir), base)

        if (sidecar == SidecarState.SATISFIED) {
            return record(candidate, Rung.R0_SATISFIED, LadderOutcome.SATISFIED)
        }

        val prior = loadState(candidate.episodeId)
        val embedded = try {
            hasEmbeddedEnglishText(file)
        } catch (e: Exception) {
            logger.warn("sub-ladder: probe failed for {}: {}", file, e.message)
            return record(candidate, Rung.R1_EMBEDDED, LadderOutcome.UNREADABLE)
        }

        val rung = nextRung(
            sidecar = sidecar,
            hasEmbeddedEnglishText = embedded,
            priority = candidate.priority,
            whisperMaxPriority = whisperMaxPriority(),
            whisperEnabled = whisperEnabled(),
            bazarrAlreadyTried = prior?.lastRung == Rung.R2_BAZARR.name,
        )

        return when (rung) {
            Rung.R0_SATISFIED -> record(candidate, rung, LadderOutcome.SATISFIED)

            Rung.R1_EMBEDDED -> {
                val ok = try { extractEmbedded(file) } catch (_: Exception) { false }
                if (ok) lastReport?.extracted?.incrementAndGet()
                record(candidate, rung, if (ok) LadderOutcome.SATISFIED else LadderOutcome.NO_SOURCE)
            }

            Rung.R2_BAZARR -> {
                val ok = try {
                    triggerBazarr(candidate.seriesId, candidate.episodeId)
                } catch (_: Exception) { false }
                if (ok) lastReport?.bazarrTriggered?.incrementAndGet()
                // Bazarr searches asynchronously: a successful trigger only
                // means "queued". The result, if any, is seen next sweep.
                record(candidate, rung, if (ok) LadderOutcome.NO_SOURCE else LadderOutcome.UPSTREAM_DOWN)
            }

            Rung.R3_WHISPER -> whisperSlot.withLock {
                val srt = try {
                    whisperTranslate(file, candidate.audioLang)
                } catch (e: Exception) {
                    logger.warn("sub-ladder: whisper failed for {}: {}", file, e.message)
                    null
                }
                if (srt.isNullOrBlank()) {
                    record(candidate, rung, LadderOutcome.UPSTREAM_DOWN)
                } else {
                    lastReport?.whispered?.incrementAndGet()
                    // Either we wrote it, or Bazarr beat us to it mid-run.
                    // Both mean the episode now has its .en.srt.
                    writeSidecarIfAbsent(dir, base, srt)
                    record(candidate, rung, LadderOutcome.SATISFIED)
                }
            }

            Rung.R4_EXHAUSTED -> {
                val outcome =
                    if (sidecar == SidecarState.VARIANT_ONLY) LadderOutcome.BLOCKED_VARIANT
                    else LadderOutcome.NO_SOURCE
                record(candidate, rung, outcome)
            }
        }
    }

    /**
     * Write `<base>.en.srt`, but never over an existing one.
     *
     * Existence is re-checked immediately before the move, closing the
     * window where Bazarr lands a sidecar during a long Whisper run.
     * Returns false when we deliberately kept the existing file.
     */
    private fun writeSidecarIfAbsent(dir: Path, base: String, content: String): Boolean {
        val target = dir.resolve("$base.en.srt")
        if (Files.exists(target)) return false
        // Unique tmp name: the deterministic one already races.
        val tmp = dir.resolve("$base.en.srt.${UUID.randomUUID()}.tmp")
        return try {
            Files.writeString(tmp, content)
            if (Files.exists(target)) {
                Files.deleteIfExists(tmp)
                false
            } else {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tmp, target)
                }
                true
            }
        } catch (e: Exception) {
            logger.warn("sub-ladder: failed writing {}: {}", target, e.message)
            Files.deleteIfExists(tmp)
            false
        }
    }

    private fun record(c: LadderCandidate, rung: Rung, outcome: LadderOutcome): LadderOutcome {
        val prior = loadState(c.episodeId)?.attempts ?: 0L
        val attempts = if (consumesAttempt(outcome)) prior + 1 else prior
        val nextRetryAt = when (outcome) {
            LadderOutcome.SATISFIED -> null
            // Retry an outage soon; it is our fault, not the file's.
            LadderOutcome.UPSTREAM_DOWN -> isoPlus(java.time.Duration.ofHours(1))
            LadderOutcome.BLOCKED_VARIANT -> isoPlus(java.time.Duration.ofDays(28))
            else -> isoPlus(backoffFor(attempts.toInt()))
        }
        saveState(c.episodeId, rung.name, outcome.name, attempts, nextRetryAt)
        return outcome
    }

    /**
     * Timestamps MUST be formatted with [Database.ISO_OFFSET], never
     * `OffsetDateTime.toString()`.
     *
     * `next_retry_at` is compared lexicographically in SQL
     * (`next_retry_at <= ?`), so the written format has to match what
     * every other writer and reader uses. The JDK prints `Z` for a zero
     * offset while `ISO_OFFSET` pins it to `+00:00`, and `'Z'` (0x5A)
     * sorts ABOVE `'+'` (0x2B) — a `Z`-formatted row would never compare
     * as due, and the episode would silently never be retried again.
     * `Database.ISO_OFFSET` exists precisely to avoid this.
     */
    private fun isoPlus(d: java.time.Duration): String =
        OffsetDateTime.now(ZoneOffset.UTC).plus(d).format(Database.ISO_OFFSET)

    private fun siblingNames(dir: Path): Set<String> = try {
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
    } catch (_: Exception) {
        emptySet()
    }

    private fun baseNameOf(file: Path): String {
        val n = file.fileName.toString()
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }
}
```

Add the import for `withLock` at the top: `import kotlinx.coroutines.sync.withLock`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubtitleLadderTest*"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadder.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/SubtitleLadderTest.kt
git commit -m "feat(sub-ladder): ladder orchestrator with gating and never-clobber writes"
```

---

### Task 8: Settings, job id, and wiring

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Constants.kt:50`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SubLadderSettingsTest.kt`

**Interfaces:**
- Consumes: `SubtitleLadder`, `LadderCandidate` (Task 7), `WhisperClient` (Task 6), `BazarrClient.triggerEpisodeSearch(sonarrSeriesId, sonarrEpisodeId, language)` (existing, currently never instantiated)
- Produces: `JobId.SUB_LADDER = "sub-ladder"`; settings `subLadderEnabled`, `subLadderWhisperEnabled`, `subLadderWhisperMaxPriority`, `intervals.subLadderIntervalMinutes`, `intervals.subLadderMaxPerSweep`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SubLadderSettingsTest {

    @Test
    fun defaults_are_conservative_and_off() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
                "PRIORITARR_SONARR_API_KEY" to "k",
                "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
                "PRIORITARR_TAUTULLI_API_KEY" to "k",
                "PRIORITARR_QBIT_URL" to "http://vpn:8080",
                "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
                "PRIORITARR_SAB_API_KEY" to "k",
            ),
        )
        assertEquals(false, s.subLadderEnabled, "must ship disabled")
        assertEquals(false, s.subLadderWhisperEnabled, "whisper rung must ship disabled")
        assertEquals(2, s.subLadderWhisperMaxPriority, "P1/P2 by default")
        assertEquals(30, s.intervals.subLadderIntervalMinutes)
        assertEquals(5, s.intervals.subLadderMaxPerSweep, "start low; no Bazarr guard on this path")
    }

    @Test
    fun env_overrides_apply() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
                "PRIORITARR_SONARR_API_KEY" to "k",
                "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
                "PRIORITARR_TAUTULLI_API_KEY" to "k",
                "PRIORITARR_QBIT_URL" to "http://vpn:8080",
                "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
                "PRIORITARR_SAB_API_KEY" to "k",
                "PRIORITARR_SUB_LADDER_ENABLED" to "true",
                "PRIORITARR_SUB_LADDER_WHISPER_ENABLED" to "true",
                "PRIORITARR_SUB_LADDER_WHISPER_MAX_PRIORITY" to "1",
            ),
        )
        assertEquals(true, s.subLadderEnabled)
        assertEquals(true, s.subLadderWhisperEnabled)
        assertEquals(1, s.subLadderWhisperMaxPriority)
    }
}
```

Check `loadSettingsFrom`'s exact required-key names first (`config/Settings.kt:504`) and adjust the map if the required set differs.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubLadderSettingsTest*"`
Expected: FAIL — `subLadderEnabled` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `Constants.kt`, after `SUB_EXTRACT`:

```kotlin
    const val SUB_LADDER = "sub-ladder"
```

In `Settings.kt`, add to the `IntervalsConfig` data class beside `subExtractIntervalMinutes`:

```kotlin
    /** Cadence of the English-SRT ladder sweep. */
    val subLadderIntervalMinutes: Int = 30,
    /**
     * Episodes touched per sweep. Deliberately low: the Bazarr endpoint
     * this drives has no adaptive-search guard, so this cap plus the
     * gates are the only pacing on provider traffic.
     */
    val subLadderMaxPerSweep: Int = 5,
```

Add the matching `Int?` fields to the override data class, the `?:` merge lines, and the YAML keys `sub_ladder_interval_minutes` / `sub_ladder_max_per_sweep`, following exactly how `subExtractIntervalMinutes` is threaded through all four places.

Add to the top-level `Settings` data class. **`bazarrUrl`, `bazarrApiKey` and `whisperUrl` do not exist yet — they must be added**, alongside the existing Tdarr fields:

```kotlin
    val subLadderEnabled: Boolean = false,
    val subLadderWhisperEnabled: Boolean = false,
    val subLadderWhisperMaxPriority: Int = 2,
    /** Bazarr's API root, including its base path. */
    val bazarrUrl: String = "http://bazarr:6767/bazarr",
    val bazarrApiKey: String? = null,
    val whisperUrl: String = "http://whisper:9000",
```

And to its construction, following the `tdarrUrl` / `tdarrPauseEnabled` pattern exactly:

```kotlin
        subLadderEnabled = (env("SUB_LADDER_ENABLED", "false") ?: "false").lowercase() in TRUTHY,
        subLadderWhisperEnabled = (env("SUB_LADDER_WHISPER_ENABLED", "false") ?: "false").lowercase() in TRUTHY,
        subLadderWhisperMaxPriority = env("SUB_LADDER_WHISPER_MAX_PRIORITY", "2")?.toIntOrNull() ?: 2,
        bazarrUrl = env("BAZARR_URL", "http://bazarr:6767/bazarr") ?: "http://bazarr:6767/bazarr",
        bazarrApiKey = env("BAZARR_API_KEY")?.takeIf { it.isNotBlank() },
        whisperUrl = env("WHISPER_URL", "http://whisper:9000") ?: "http://whisper:9000",
```

The corresponding compose entries (add to `media-stack-v3.yml` in the `D:\docker` repo, which is a **separate repo** — do not commit it with the prioritarr changes):

```yaml
      PRIORITARR_BAZARR_URL: http://bazarr:6767/bazarr
      PRIORITARR_BAZARR_API_KEY: ${BAZARR_API_KEY}
      PRIORITARR_WHISPER_URL: http://whisper:9000
      PRIORITARR_SUB_LADDER_ENABLED: "false"
      PRIORITARR_SUB_LADDER_WHISPER_ENABLED: "false"
```

In `Main.kt`, instantiate the clients (note `BazarrClient` is currently never constructed anywhere) and the ladder, then register the job. Place beside the existing `subtitleExtractor` wiring:

```kotlin
    // BazarrClient has existed but was never constructed anywhere until now.
    // Constructor order is (baseUrl, apiKey, http) — see clients/Bazarr.kt:25.
    val bazarrClient = org.yoshiz.app.prioritarr.backend.clients.BazarrClient(
        baseUrl = settings.bazarrUrl,
        apiKey = settings.bazarrApiKey ?: "",
        http = healthHttp,
    )
    val whisperClient = org.yoshiz.app.prioritarr.backend.clients.WhisperClient(
        http = healthHttp,
        baseUrl = settings.whisperUrl,
    )

    val subtitleLadder = org.yoshiz.app.prioritarr.backend.reconcile.SubtitleLadder(
        candidates = { buildLadderCandidates(sonarr, priorityService, db, liveSettings(db, settings)) },
        hasEmbeddedEnglishText = { path ->
            org.yoshiz.app.prioritarr.backend.reconcile.FfmpegSubtitleIo.probe(path).any { s ->
                s.codecName.lowercase() in org.yoshiz.app.prioritarr.backend.reconcile.SubtitleExtractor.TEXT_CODECS &&
                    (s.language?.lowercase()?.trim().let { it == "en" || it == "eng" || it == "english" } ||
                        (s.language.isNullOrBlank() && s.title?.lowercase()?.contains("english") == true))
            }
        },
        extractEmbedded = { path -> subtitleExtractor.extractForFile(path).extracted > 0 },
        triggerBazarr = { seriesId, episodeId ->
            bazarrClient.triggerEpisodeSearch(seriesId, episodeId, "en") != null
        },
        whisperTranslate = { path, lang ->
            val tmpWav = java.nio.file.Files.createTempFile("sub-ladder-", ".wav")
            try {
                if (org.yoshiz.app.prioritarr.backend.reconcile.FfmpegAudioIo.extractWav(path, tmpWav)) {
                    whisperClient.translateToSrt(tmpWav, lang)
                } else null
            } finally {
                java.nio.file.Files.deleteIfExists(tmpWav)
            }
        },
        plexSessions = { plex?.activeSessionCountOrNull() },
        congested = { searchQueueControl.isCongested() },
        maxPerSweep = { liveSettings(db, settings).intervals.subLadderMaxPerSweep },
        whisperEnabled = { liveSettings(db, settings).subLadderWhisperEnabled },
        whisperMaxPriority = { liveSettings(db, settings).subLadderWhisperMaxPriority },
        resumeAfterIdleTicks = { 3 },
        loadState = { id ->
            db.getLadderState(id)?.let {
                org.yoshiz.app.prioritarr.backend.reconcile.LadderState(it.last_rung, it.attempts)
            }
        },
        saveState = { id, rung, outcome, attempts, next ->
            db.upsertLadderState(id, rung, outcome, attempts, next)
        },
    )
```

Register the job in the same `add(JobDefinition(...))` list as `SUB_EXTRACT`:

```kotlin
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.SUB_LADDER,
                cadenceMinutes = { liveSettings(db, settings).intervals.subLadderIntervalMinutes.toLong() },
                prerequisites = { liveSettings(db, settings).subLadderEnabled },
                // LIGHT for the same reason sub-extract is: the single HEAVY
                // slot per tick is permanently held by the refresh-* jobs.
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                firstRunDelayMinutes = 3,
                run = {
                    val r = subtitleLadder.sweep()
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(
                        summary = "considered=${r.considered} satisfied=${r.satisfied} " +
                            "bazarr=${r.bazarrTriggered} whisper=${r.whispered} " +
                            "noSource=${r.noSource} upstreamDown=${r.upstreamDown}",
                        noop = r.considered == 0,
                    )
                },
            ))
```

Implement `buildLadderCandidates` as a private suspend helper in `Main.kt`. It reuses the priority ordering already wired for `sub-extract`:

```kotlin
/**
 * Enumerate ladder candidates in prioritarr priority order (P1 first).
 *
 * Sonarr is the source of episode identity because Bazarr's search
 * endpoint needs `seriesid` + `episodeid`, and matching on the
 * episodeFile path is the only robust link from a file on disk back to
 * an episode row.
 */
private suspend fun buildLadderCandidates(
    sonarr: SonarrClient,
    priorityService: PriorityService,
    db: Database,
    s: Settings,
): List<LadderCandidate> {
    val roots = s.subExtractPaths.map { it.trimEnd('/') }
    if (roots.isEmpty()) return emptyList()

    val out = mutableListOf<LadderCandidate>()
    val series = sonarr.getAllSeries().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (roots.none { path == it || path.startsWith("$it/") }) return@mapNotNull null
        Triple(id, path, priorityService.priorityForSeries(id).priority)
    }.sortedBy { it.third }

    val budget = s.intervals.subLadderMaxPerSweep.coerceAtLeast(0)
    for ((seriesId, _, priority) in series) {
        // Stop as soon as we have a full sweep's worth. Series are already
        // priority-ordered, so the highest-priority work is found first, and
        // we avoid a per-series Sonarr call for all ~300 series on every
        // sweep — that fan-out is what starved Sonarr's SQLite in the
        // 2026-08-14 incident, and the episode-cache job already pays it hourly.
        if (out.size >= budget) break
        for (el in sonarr.getEpisodes(seriesId)) {
            val o = el as? JsonObject ?: continue
            if (o["hasFile"]?.jsonPrimitive?.contentOrNull != "true") continue
            val epId = o["id"]?.jsonPrimitive?.longOrNull ?: continue
            val filePath = (o["episodeFile"] as? JsonObject)
                ?.get("path")?.jsonPrimitive?.contentOrNull ?: continue
            val due = db.getLadderState(epId)?.next_retry_at
            if (due != null && due > Database.nowIsoOffset()) continue
            if (out.size >= budget) break
            out += LadderCandidate(
                videoPath = java.nio.file.Paths.get(filePath),
                seriesId = seriesId,
                episodeId = epId,
                priority = priority,
                audioLang = "ja",
            )
        }
    }
    return out
}
```

`SonarrClient.getEpisodes(seriesId)` (`clients/Sonarr.kt:42`) does **not** currently request episode files, so `episodeFile.path` will be absent. Widen it:

```kotlin
    suspend fun getEpisodes(seriesId: Long): JsonArray =
        get(
            "/api/v3/episode",
            mapOf("seriesId" to seriesId.toString(), "includeEpisodeFile" to "true"),
        ).jsonArray
```

Note `hasFile` arrives as a JSON boolean, so `jsonPrimitive.contentOrNull` yields the string `"true"` — the comparison in `buildLadderCandidates` is correct as written, but do not "simplify" it to a boolean cast without checking.

- [ ] **Step 4: Run the full suite**

Run: `.\gradlew.bat :backend:test`
Expected: PASS, all tests including the new ones.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SubLadderSettingsTest.kt
git commit -m "feat(sub-ladder): settings, job registration, and client wiring"
```

---

### Task 9: On-demand single-episode trigger

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/app/SubLadderRouteTest.kt`

**Interfaces:**
- Consumes: `SubtitleLadder.runOne(candidate)`, `LadderCandidate` (Task 7)
- Produces: `POST /api/v2/subtitles/ladder/{episodeId}` returning `{"outcome":"SATISFIED"}`

Bypasses the priority gate and backoff — an explicit request is the user saying "now" — but still runs inside `runOne`, so the never-clobber and Whisper-serialisation guarantees hold.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubLadderRouteTest {

    @Test
    fun posting_an_episode_id_runs_the_ladder_once() = testApplication {
        var ranFor: Long? = null
        application {
            subtitleLadderRoutes(
                runLadderFor = { episodeId -> ranFor = episodeId; "SATISFIED" },
            )
        }

        val resp = client.post("/api/v2/subtitles/ladder/25749")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue("SATISFIED" in resp.bodyAsText())
        assertEquals(25749L, ranFor)
    }

    @Test
    fun unknown_episode_is_404() = testApplication {
        application {
            subtitleLadderRoutes(runLadderFor = { null })
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/v2/subtitles/ladder/999").status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :backend:test --tests "*SubLadderRouteTest*"`
Expected: FAIL — `subtitleLadderRoutes` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `app/Module.kt` (match the file's existing routing style and auth wrapper):

```kotlin
/**
 * On-demand ladder trigger. Skips the priority gate and backoff — an
 * explicit request means "do this one now" — but goes through
 * [SubtitleLadder.runOne], so never-clobber and Whisper serialisation
 * still apply.
 *
 * @param runLadderFor returns the outcome name, or null when the
 *   episode is unknown to Sonarr.
 */
fun Application.subtitleLadderRoutes(runLadderFor: suspend (Long) -> String?) {
    routing {
        post("/api/v2/subtitles/ladder/{episodeId}") {
            val id = call.parameters["episodeId"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "episodeId must be numeric"))
                return@post
            }
            val outcome = runLadderFor(id)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
            } else {
                call.respond(HttpStatusCode.OK, mapOf("outcome" to outcome))
            }
        }
    }
}
```

Wire it in `Main.kt` where the other routes are installed:

```kotlin
        subtitleLadderRoutes { episodeId ->
            buildLadderCandidates(sonarr, priorityService, db, liveSettings(db, settings))
                .firstOrNull { it.episodeId == episodeId }
                ?.let { subtitleLadder.runOne(it).name }
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :backend:test --tests "*SubLadderRouteTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/app/SubLadderRouteTest.kt
git commit -m "feat(sub-ladder): on-demand single-episode trigger endpoint"
```

---

### Task 10: README and rollout

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above
- Produces: no code

- [ ] **Step 1: Add the job to the background-jobs table**

Insert after the **Sub-extract** row:

```markdown
| **Sub-ladder** | 30 min | Guarantees every anime episode a plain `.en.srt`. Climbs: embedded extract → Bazarr provider search → Whisper JP→EN. Priority-ordered; pauses while Plex is streaming or Sonarr has P1/P2 searches in flight. `SUB_LADDER_ENABLED=true`. |
```

- [ ] **Step 2: Document the env vars**

Add under the optional env vars list, beside the sub-extract bullet:

```markdown
- **Sub-ladder** — `SUB_LADDER_ENABLED`, `SUB_LADDER_WHISPER_ENABLED`, `SUB_LADDER_WHISPER_MAX_PRIORITY` (default 2 = P1/P2). Cadence and per-sweep cap are YAML/DB-only: `sub_ladder_interval_minutes`, `sub_ladder_max_per_sweep`.
```

- [ ] **Step 3: Add a short capability note**

Add after the Watch sources section:

```markdown
### English subtitle coverage

Plex cannot soft-serve ASS, so an episode whose only English subtitle is
an embedded ASS track forces a full transcode. The sub-ladder job
guarantees an external `.en.srt` instead, trying the cheapest source
first: extract an embedded text track, else ask Bazarr's providers, else
Whisper the Japanese audio straight to English (`task=translate`, one
pass). Whisper is CPU-bound and therefore priority-gated, globally
serialised, and paused while anything is streaming.

Bazarr configuration is untouched — `adaptive_searching` stays on and
continues to govern Bazarr's own scheduled sweep. prioritarr paces only
the searches it triggers, via `subtitle_ladder_state`.
```

- [ ] **Step 4: Verify the build still passes**

Run: `.\gradlew.bat :backend:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs(readme): document the sub-ladder job and its env vars"
```

---

## Rollout after implementation

Enable in this order, verifying each before the next:

1. Deploy with everything off. Confirm the job registers and no-ops.
2. `SUB_LADDER_ENABLED=true`, whisper still off, `sub_ladder_max_per_sweep: 5`. Watch `considered`/`satisfied` in the job summary and confirm Bazarr traffic stays modest — this path has **no** adaptive-search guard, so the cap is the only pacing.
3. Once R2's yield plateaus, `SUB_LADDER_WHISPER_ENABLED=true` with `SUB_LADDER_WHISPER_MAX_PRIORITY=1` (P1 only). Verify a single Whisper run completes and that it never runs while Plex is streaming.
4. Widen to P2 if CPU headroom allows.

Bazarr settings stay exactly as they are throughout.
