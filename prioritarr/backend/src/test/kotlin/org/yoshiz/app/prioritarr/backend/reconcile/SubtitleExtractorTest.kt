package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleExtractorTest {

    /** Records every extract() call the reconciler makes. */
    private class ExtractRecorder {
        data class Call(val file: Path, val streamIndex: Int, val target: Path)
        val calls = mutableListOf<Call>()

        /** Behaves like ffmpeg: writes the tmp file the reconciler expects. */
        val seam: suspend (Path, Int, Path) -> Boolean = { file, idx, target ->
            calls += Call(file, idx, target)
            Files.writeString(target, SAMPLE_SRT)
            true
        }
    }

    private fun tempRoot(): Path =
        Files.createTempDirectory("sub-extract-test").also { it.toFile().deleteOnExit() }

    private fun touch(dir: Path, name: String, content: String = "") {
        dir.createDirectories()
        Files.writeString(dir.resolve(name), content)
    }

    private fun extractor(
        root: Path,
        streams: List<SubStream>,
        recorder: ExtractRecorder,
        langs: List<String> = listOf("en"),
        maxPerRun: Int = 25,
        orderedDirs: (suspend () -> List<String>)? = null,
    ) = SubtitleExtractor(
        paths = { listOf(root.toString()) },
        langs = { langs },
        maxPerRun = { maxPerRun },
        probe = { streams },
        extract = recorder.seam,
        orderedDirs = orderedDirs,
    )

    // (a) embedded eng text track + no sidecar → extract with right index & target.
    @Test fun eng_text_track_no_sidecar_extracts() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E01.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec).sweep()

        assertEquals(1, rec.calls.size)
        assertEquals(0, rec.calls[0].streamIndex)
        assertTrue(Files.exists(root.resolve("Show.S01E01.en.srt")), "final sidecar written")
        assertFalse(Files.exists(root.resolve("Show.S01E01.en.srt.tmp")), "tmp renamed away")
        assertEquals(1, report.extracted)
        assertEquals(1, report.filesScanned)
    }

    // (b) existing sidecar → skipped, extract never called.
    @Test fun existing_sidecar_is_skipped() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E02.mkv")
        touch(root, "Show.S01E02.en.srt", "already here")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec).sweep()

        assertEquals(0, rec.calls.size)
        assertEquals(1, report.skippedHasSidecar)
        assertEquals(0, report.extracted)
        // Existing sub is untouched.
        assertEquals("already here", Files.readString(root.resolve("Show.S01E02.en.srt")))
    }

    // (b') the language-less <base>.srt is NOT the strict bar (only an exact
    // <base>.<lang2>.srt counts) — it must no longer block extraction. See
    // hasSidecar's narrowing: a variant/uncertain-language sidecar must still
    // permit the free rung, same rationale as the .en.hi.srt case below.
    @Test fun plain_srt_sidecar_no_longer_blocks_extraction() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E03.mkv")
        touch(root, "Show.S01E03.srt", "plain")
        val streams = listOf(SubStream(index = 0, codecName = "subrip", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec, langs = listOf("en", "fr")).sweep()

        // en has a matching embedded track and no exact Show.S01E03.en.srt → extracted.
        assertEquals(1, rec.calls.size)
        assertEquals(1, report.extracted)
        assertEquals(0, report.skippedHasSidecar)
        // fr has no matching embedded track → skipped for lack of a text track, not sidecar coverage.
        assertEquals(1, report.skippedNoTextTrack)
        // The pre-existing bare .srt is left untouched.
        assertEquals("plain", Files.readString(root.resolve("Show.S01E03.srt")))
    }

    // (c) only image-codec subs → skipped, extract never called.
    @Test fun image_codec_only_is_skipped() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E04.mkv")
        val streams = listOf(
            SubStream(index = 0, codecName = "hdmv_pgs_subtitle", language = "eng"),
            SubStream(index = 1, codecName = "dvd_subtitle", language = "eng"),
        )
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec).sweep()

        assertEquals(0, rec.calls.size)
        assertEquals(1, report.skippedNoTextTrack)
        assertEquals(0, report.extracted)
    }

    // (d) multiple tracks → default dialogue chosen over a forced signs track.
    @Test fun default_track_chosen_over_signs_forced() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E05.mkv")
        val streams = listOf(
            SubStream(index = 0, codecName = "ass", language = "eng", title = "Signs/Songs", forced = true),
            SubStream(index = 1, codecName = "ass", language = "eng", title = "Full Dialogue", default = true),
        )
        val rec = ExtractRecorder()
        extractor(root, streams, rec).sweep()

        assertEquals(1, rec.calls.size)
        assertEquals(1, rec.calls[0].streamIndex) // the default dialogue track
    }

    // (d') no default → non-forced non-signs dialogue chosen over forced signs.
    @Test fun dialogue_chosen_over_signs_when_no_default() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E06.mkv")
        val streams = listOf(
            SubStream(index = 0, codecName = "ass", language = "eng", title = "Signs & Songs", forced = true),
            SubStream(index = 1, codecName = "ass", language = "eng", title = "Dialogue"),
        )
        val rec = ExtractRecorder()
        extractor(root, streams, rec).sweep()

        assertEquals(1, rec.calls.size)
        assertEquals(1, rec.calls[0].streamIndex)
    }

    // (f) maxPerRun cap respected — never extract more than the cap.
    @Test fun max_per_run_cap_respected() = runTest {
        val root = tempRoot()
        for (i in 1..5) touch(root, "Show.S01E0$i.cap.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec, maxPerRun = 2).sweep()

        assertEquals(2, rec.calls.size)
        assertEquals(2, report.extracted)
        assertTrue(report.capHit, "cap-hit flagged so truncation isn't silent")
    }

    // ---- priority-ordered sweep (orderedDirs seam) ----

    // orderedDirs returns two dirs in a chosen order → the first dir's files
    // are all extracted before the second dir's, and the shared cap bounds
    // TOTAL extractions across both dirs.
    @Test fun ordered_dirs_processed_in_priority_order() = runTest {
        val root = tempRoot()
        val p1 = root.resolve("p1")
        val p5 = root.resolve("p5")
        touch(p1, "P1.S01E01.mkv")
        touch(p5, "P5.S01E01.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        // Deliberately supply p1 (high priority) before p5.
        val report = extractor(
            root, streams, rec,
            orderedDirs = { listOf(p1.toString(), p5.toString()) },
        ).sweep()

        assertEquals(2, rec.calls.size)
        assertTrue(rec.calls[0].file.toString().contains("P1"), "P1 extracted first")
        assertTrue(rec.calls[1].file.toString().contains("P5"), "P5 extracted second")
        assertEquals(2, report.extracted)
    }

    // The maxPerRun cap still caps TOTAL extractions across all ordered dirs.
    @Test fun ordered_dirs_cap_bounds_total_across_dirs() = runTest {
        val root = tempRoot()
        val a = root.resolve("a")
        val b = root.resolve("b")
        touch(a, "A.S01E01.mkv")
        touch(a, "A.S01E02.mkv")
        touch(b, "B.S01E01.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(
            root, streams, rec, maxPerRun = 2,
            orderedDirs = { listOf(a.toString(), b.toString()) },
        ).sweep()

        assertEquals(2, rec.calls.size)
        assertEquals(2, report.extracted)
        assertTrue(report.capHit, "cap hit across dirs")
        // Both extractions come from the first dir (cap spent before dir b).
        assertTrue(rec.calls.all { it.file.toString().contains("${File.separator}a${File.separator}") })
    }

    // orderedDirs returning emptyList → fall back to walking paths() (flat).
    @Test fun ordered_dirs_empty_falls_back_to_flat_walk() = runTest {
        val root = tempRoot()
        touch(root, "Flat.S01E01.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(
            root, streams, rec,
            orderedDirs = { emptyList() },
        ).sweep()

        assertEquals(1, rec.calls.size)
        assertTrue(Files.exists(root.resolve("Flat.S01E01.en.srt")))
        assertEquals(1, report.extracted)
    }

    // Non-existent dirs in the ordered list are skipped gracefully.
    @Test fun ordered_dirs_skips_missing_dirs() = runTest {
        val root = tempRoot()
        val real = root.resolve("real")
        touch(real, "R.S01E01.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(
            root, streams, rec,
            orderedDirs = { listOf(root.resolve("ghost").toString(), real.toString()) },
        ).sweep()

        assertEquals(1, rec.calls.size)
        assertEquals(1, report.extracted)
    }

    // ---- orderSeriesDirsByPriority (pure ordering core) ----

    @Test fun order_series_p1_before_p5() {
        val ordered = orderSeriesDirsByPriority(
            listOf(
                SeriesDir("/storage/anime/Zeta", priority = 5),
                SeriesDir("/storage/anime/Alpha", priority = 1),
                SeriesDir("/storage/anime/Mid", priority = 3),
            ),
            subExtractPaths = listOf("/storage/anime"),
        )
        assertEquals(
            listOf("/storage/anime/Alpha", "/storage/anime/Mid", "/storage/anime/Zeta"),
            ordered,
        )
    }

    @Test fun order_series_filters_out_paths_not_under_roots() {
        val ordered = orderSeriesDirsByPriority(
            listOf(
                SeriesDir("/storage/anime/Keep", priority = 2),
                SeriesDir("/storage/series/Drop", priority = 1),
                // Prefix-of-a-sibling must NOT match: "/storage/anime2" is not under "/storage/anime".
                SeriesDir("/storage/anime2/AlsoDrop", priority = 1),
            ),
            subExtractPaths = listOf("/storage/anime"),
        )
        assertEquals(listOf("/storage/anime/Keep"), ordered)
    }

    @Test fun order_series_tie_break_by_path_at_equal_priority() {
        val ordered = orderSeriesDirsByPriority(
            listOf(
                SeriesDir("/storage/anime/Charlie", priority = 2),
                SeriesDir("/storage/anime/Bravo", priority = 2),
                SeriesDir("/storage/anime/Alpha", priority = 2),
            ),
            subExtractPaths = listOf("/storage/anime"),
        )
        assertEquals(
            listOf("/storage/anime/Alpha", "/storage/anime/Bravo", "/storage/anime/Charlie"),
            ordered,
        )
    }

    @Test fun order_series_exact_root_and_trailing_slash_root_match() {
        val ordered = orderSeriesDirsByPriority(
            listOf(
                SeriesDir("/storage/anime", priority = 4),      // exact root match
                SeriesDir("/storage/anime/Sub", priority = 1),
            ),
            subExtractPaths = listOf("/storage/anime/"),         // trailing slash tolerated
        )
        assertEquals(listOf("/storage/anime/Sub", "/storage/anime"), ordered)
    }

    @Test fun order_series_empty_input_empty_output() {
        assertEquals(
            emptyList(),
            orderSeriesDirsByPriority(emptyList(), subExtractPaths = listOf("/storage/anime")),
        )
    }

    // ---- extractForFile: event-driven single-file entry point ----

    // (a) embedded eng text track + no sidecar → extract once, extracted=1.
    @Test fun extractForFile_eng_text_no_sidecar_extracts_once() = runTest {
        val root = tempRoot()
        touch(root, "Show.S02E01.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val file = root.resolve("Show.S02E01.mkv")
        val report = extractor(root, streams, rec).extractForFile(file)

        assertEquals(1, rec.calls.size)
        assertEquals(0, rec.calls[0].streamIndex)
        assertEquals(file, rec.calls[0].file)
        assertTrue(Files.exists(root.resolve("Show.S02E01.en.srt")), "final sidecar written")
        assertEquals(1, report.extracted)
        assertEquals(1, report.filesScanned)
    }

    // (b) existing sidecar → skipped, extract never called.
    @Test fun extractForFile_existing_sidecar_is_skipped() = runTest {
        val root = tempRoot()
        touch(root, "Show.S02E02.mkv")
        touch(root, "Show.S02E02.en.srt", "already here")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec).extractForFile(root.resolve("Show.S02E02.mkv"))

        assertEquals(0, rec.calls.size)
        assertEquals(1, report.skippedHasSidecar)
        assertEquals(0, report.extracted)
        assertEquals("already here", Files.readString(root.resolve("Show.S02E02.en.srt")))
    }

    // (c) only image-codec subs → skipped, extract never called.
    @Test fun extractForFile_image_codec_only_is_skipped() = runTest {
        val root = tempRoot()
        touch(root, "Show.S02E03.mkv")
        val streams = listOf(SubStream(index = 0, codecName = "hdmv_pgs_subtitle", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec).extractForFile(root.resolve("Show.S02E03.mkv"))

        assertEquals(0, rec.calls.size)
        assertEquals(1, report.skippedNoTextTrack)
        assertEquals(0, report.extracted)
    }

    // non-video extension → no-op (filesScanned stays 0, extract never called).
    @Test fun extractForFile_non_video_is_noop() = runTest {
        val root = tempRoot()
        touch(root, "notes.txt")
        val streams = listOf(SubStream(index = 0, codecName = "ass", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec).extractForFile(root.resolve("notes.txt"))

        assertEquals(0, rec.calls.size)
        assertEquals(0, report.filesScanned)
        assertEquals(0, report.extracted)
    }

    // (e) font-tag stripping keeps b/i/u, drops <font>.
    @Test fun strip_font_tags_pure_function() {
        val input = "<font color=\"#FFFFFF\"><b>Bold</b> and <i>italic</i></font> and <u>under</u>"
        val out = stripFontTags(input)
        assertFalse(out.contains("<font", ignoreCase = true))
        assertFalse(out.contains("</font>", ignoreCase = true))
        assertTrue(out.contains("<b>Bold</b>"))
        assertTrue(out.contains("<i>italic</i>"))
        assertTrue(out.contains("<u>under</u>"))
    }

    /**
     * Originally this asserted that braces without a leading backslash
     * were preserved as ordinary text. That premise was wrong for
     * ASS-derived SRT: a sweep of the library found fansub comments
     * written exactly that way -- `{overlap}`, `{Preview}`, `{eyecatch}`,
     * `{volume: extend sub a bit}` -- all of which an ASS renderer hides
     * and which were being shown to the viewer.
     */
    @Test fun strip_removes_every_ass_brace_block() {
        assertEquals("Top text styled end", stripFontTags("{\\an8}Top text {\\i1}styled{\\i0} end"))
        assertEquals("", stripFontTags("{not an override}"))
    }

    @Test fun strip_font_tags_multiline_and_uppercase() {
        val input = "1\n00:00:01,000 --> 00:00:02,000\n<FONT face=\"Arial\" size=\"20\">Line one</FONT>\n<font>Line two</font>"
        val out = stripFontTags(input)
        assertFalse(out.contains("font", ignoreCase = true))
        assertTrue(out.contains("Line one"))
        assertTrue(out.contains("Line two"))
    }

    // selectSubStream unit coverage (decision logic in isolation).
    @Test fun select_returns_null_on_empty() {
        assertNull(selectSubStream(emptyList()))
    }

    /**
     * A signs/songs track carries typesetting and karaoke, never
     * dialogue, so it must lose to a real dialogue track even when the
     * release flags it `default`. 52 files in the library were extracted
     * from a `default`-flagged signs track before this was enforced.
     */
    @Test fun select_skips_signs_even_when_flagged_default() {
        val s = selectSubStream(
            listOf(
                SubStream(0, "ass", "eng", "Full Subtitles"),
                SubStream(1, "ass", "eng", "[FFF] Signs", default = true),
            ),
        )
        assertEquals(0, s?.index)
    }

    @Test fun select_prefers_default_among_dialogue_tracks() {
        val s = selectSubStream(
            listOf(
                SubStream(0, "ass", "eng", "Dialogue"),
                SubStream(1, "ass", "eng", "Full Subtitles", default = true),
            ),
        )
        assertEquals(1, s?.index)
    }

    /**
     * When the file only ships signs/songs there is no dialogue to
     * extract. Returning one anyway wrote a junk sidecar that also
     * satisfied the ladder's "has .en.srt" check, so the episode never
     * climbed to Bazarr or Whisper. Skip instead.
     */
    @Test fun select_returns_null_when_only_signs_tracks_exist() {
        assertNull(
            selectSubStream(
                listOf(
                    SubStream(0, "ass", "eng", "Signs & Songs"),
                    SubStream(1, "ass", "eng", "Signs/Songs [GJM]", forced = true),
                ),
            ),
        )
    }

    @Test fun select_falls_back_to_forced_dialogue_when_thats_all_there_is() {
        val s = selectSubStream(
            listOf(
                SubStream(0, "ass", "eng", "Signs", default = true),
                SubStream(1, "ass", "eng", "Full Subtitles", forced = true),
            ),
        )
        assertEquals(1, s?.index)
    }

    @Test fun select_keeps_untitled_tracks_which_are_usually_dialogue() {
        val s = selectSubStream(listOf(SubStream(0, "ass", "eng", null)))
        assertEquals(0, s?.index)
    }

    // --- sanitizeSrt: drop ASS drawing cues that ffmpeg flattens into text ---

    /**
     * A fansub "Full Subtitles" track carries dialogue AND typesetting in
     * one stream: Assassination Classroom S02E05 has 354 dialogue events
     * and 12,886 `\p1` vector-drawing events. ffmpeg renders the drawing
     * commands as cue text, so they must be dropped after conversion --
     * picking a better track cannot help here.
     */
    @Test fun sanitize_drops_drawing_cues_and_renumbers() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            Having obtained certain information,

            2
            00:00:02,000 --> 00:00:03,000
            m 50 0 b 22 0 0 22 0 50 0 78 22 100 50

            3
            00:00:03,000 --> 00:00:04,000
            Or so our cover story went.
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertFalse(out.contains("m 50 0 b"))
        assertTrue(out.contains("Having obtained certain information,"))
        assertTrue(out.contains("Or so our cover story went."))
        // surviving cues are renumbered 1..n with no gap
        assertEquals(listOf("1", "2"), Regex("""(?m)^\d+$""").findAll(out).map { it.value }.toList())
    }

    /** Numbers and punctuation are not drawing commands. */
    @Test fun sanitize_keeps_numeric_dialogue() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            555-0199 555-0123

            2
            00:00:02,000 --> 00:00:03,000
            1997, 1998, 1999, 2000
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertTrue(out.contains("555-0199 555-0123"))
        assertTrue(out.contains("1997, 1998, 1999, 2000"))
    }

    @Test fun sanitize_drops_cues_left_empty_by_tag_stripping() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            {\p1}

            2
            00:00:02,000 --> 00:00:03,000
            Real line.
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertEquals(1, Regex("-->").findAll(out).count())
        assertTrue(out.contains("Real line."))
    }

    @Test fun sanitize_preserves_multi_line_cue_text() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            we were here on a secret
            investigation into the truth.
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertTrue(out.contains("we were here on a secret"))
        assertTrue(out.contains("investigation into the truth."))
    }

    /**
     * ASS drawing/clip markers open with `{=` rather than `{\`, so the
     * override stripper missed them: 86 - Eighty Six S01E14 was written
     * with 4,463 cues reading `{=146}d`, `o`, `n` -- per-character
     * karaoke that passed every other check.
     */
    @Test fun sanitize_strips_ass_equals_markers() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            {=146}Real dialogue here.
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertFalse(out.contains("{=146}"))
        assertTrue(out.contains("Real dialogue here."))
    }

    /**
     * An animated ASS sign emits one cue per frame. Akame ga Kill S01E06
     * carried "Congratulations" 226 times in 40 ms slices across 4.7 s,
     * interleaved with two other phrases -- 678 cues for three lines of
     * on-screen text, which Plex renders as a flicker. Cues sharing text
     * and contiguous timing collapse into one.
     */
    @Test fun sanitize_merges_frame_by_frame_animated_signs() {
        val cues = (0 until 60).joinToString("\n\n") { i ->
            val a = 1000 + i * 40
            val b = a + 40
            "${i + 1}\n${ms(a)} --> ${ms(b)}\nCongratulations"
        }
        val out = sanitizeSrt(cues)
        assertEquals(1, Regex("-->").findAll(out).count())
        assertTrue(out.contains("00:00:01,000 --> 00:00:03,400"))
    }

    /** Interleaved animated phrases each collapse to one cue. */
    @Test fun sanitize_merges_interleaved_animated_signs() {
        val sb = StringBuilder()
        var n = 0
        for (i in 0 until 30) {
            val a = 1000 + i * 40
            for (t in listOf("Congratulations", "on Getting Your Own", "Imperial Relic")) {
                sb.append("${++n}\n${ms(a)} --> ${ms(a + 40)}\n$t\n\n")
            }
        }
        val out = sanitizeSrt(sb.toString())
        assertEquals(3, Regex("-->").findAll(out).count())
    }

    /** The same line said again much later stays a separate cue. */
    @Test fun sanitize_keeps_distant_repeats_separate() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            Yeah!

            2
            00:05:00,000 --> 00:05:01,000
            Yeah!
        """.trimIndent()
        assertEquals(2, Regex("-->").findAll(sanitizeSrt(raw)).count())
    }

    /** Ordinary dialogue is left alone and stays in time order. */
    @Test fun sanitize_leaves_dialogue_order_intact() {
        val raw = """
            1
            00:00:12,740 --> 00:00:15,070
            So this is the capital's red-light district?

            2
            00:00:15,070 --> 00:00:16,370
            It makes me a bit nervous.

            3
            00:00:16,370 --> 00:00:19,370
            I like your honesty.
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertEquals(3, Regex("-->").findAll(out).count())
        assertTrue(out.indexOf("red-light") < out.indexOf("nervous"))
        assertTrue(out.indexOf("nervous") < out.indexOf("honesty"))
    }

    /**
     * A karaoke highlight shape is emitted glued to its syllable:
     * `<b>m 0 1 l 1 2 l 2 1 l 1 0Fu</b>`. Whole-cue matching missed
     * these -- 844 of them across 22 of 25 files in one sweep. Real
     * dialogue never begins with an ASS drawing path.
     */
    @Test fun drawing_cue_detects_a_leading_run_glued_to_text() {
        assertTrue(isDrawingCue("m 0 1 l 1 2 l 2 1 l 1 0Fu"))
        assertTrue(isDrawingCue("<b>m 0 1 l 1 2 l 2 1 l 1 0ka</b>"))
        assertTrue(isDrawingCue("m 0 -481 l 1920 -481 1920 -457 0 -457sono kyara mo"))
    }

    @Test fun drawing_cue_ignores_numeric_dialogue() {
        assertFalse(isDrawingCue("555-0199 555-0123"))
        assertFalse(isDrawingCue("1997, 1998, 1999, 2000"))
        assertFalse(isDrawingCue("Meet me at 5, 10 blocks north."))
        assertFalse(isDrawingCue("<b>My name is Mira, 17 years old.</b>"))
    }

    @Test fun sanitize_removes_karaoke_shape_cues() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            <b>m 0 1 l 1 2 l 2 1 l 1 0Fu</b>

            2
            00:00:02,000 --> 00:00:03,000
            <b>Satou's gonna kill a whole bunch of people.</b>
        """.trimIndent()
        val out = sanitizeSrt(raw)
        assertFalse(out.contains("l 1 2 l 2 1"))
        assertTrue(out.contains("Satou's gonna kill a whole bunch of people."))
    }

    /**
     * Every `{...}` block in ASS is markup or a typesetter comment and
     * is never rendered. Matching only `{\` and `{=` left 28,845 tags
     * across 24 of 25 files in one sweep: per-letter karaoke colour runs
     * written `{*\c&H...}`, plus editorial notes like `{Preview}` and
     * `{volume: extend sub a bit}`.
     */
    @Test fun strip_removes_asterisk_override_blocks() {
        val out = stripFontTags("""S{*\fax1.294\c&H424649&}t{*\fs15.267}u""")
        assertEquals("Stu", out)
    }

    @Test fun strip_removes_typesetter_comment_blocks() {
        assertEquals("Grab the cable", stripFontTags("{overlap}Grab the cable"))
        assertEquals("Nagai Kei", stripFontTags("Nag{}ai Kei"))
        assertEquals("Run!", stripFontTags("{volume: extend sub a bit}Run!"))
    }

    @Test fun strip_keeps_bold_and_italic() {
        assertEquals("<b><i>Run!</i></b>", stripFontTags("""<b><i>{\an8}Run!</i></b>"""))
    }

    /**
     * ASS escapes have no meaning in SRT and were reaching the viewer
     * as literal text -- 58 of them across 12 of 25 files in one sweep,
     * including "I might have \h\h\h...\h\hdifferent interests."
     * `\N` and `\n` are line breaks; `\h` is a hard space.
     */
    @Test fun strip_converts_ass_escapes() {
        assertEquals("Storage Room", stripFontTags("""Storage\h\h\h\hRoom"""))
        assertEquals("Alya Hides Her", stripFontTags("""Alya Hides Her\h\h"""))
        assertEquals("line one\nline two", stripFontTags("""line one\Nline two"""))
    }

    @Test fun strip_collapses_runs_of_spaces() {
        assertEquals(
            "I might have different interests.",
            stripFontTags("""I might have \h\h\h\h\h\h\h\h\h\h\h\hdifferent interests."""),
        )
    }

    /** A lone backslash in ordinary text is left alone. */
    @Test fun strip_keeps_unrelated_backslashes() {
        assertEquals("""C:\Users\me""", stripFontTags("""C:\Users\me"""))
    }

    // --- filterAssDialogue: drop non-dialogue events using ASS style names ---

    private val assHeader = """
        [Script Info]
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname
        Style: Default,Arial
        Style: OP-AJIN-Romaji,Arial
        Style: Sign #7,Arial

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    private fun ev(style: String, text: String) =
        "Dialogue: 0,0:00:01.00,0:00:02.00,$style,,0,0,0,,$text"

    /**
     * The real fix for karaoke and signs. Style names survive in ASS and
     * are destroyed by conversion to SRT, so text heuristics downstream
     * could only guess: Ajin S01E01 hides 171 `Main Dialog` events under
     * 20,925 `OP-AJIN-Romaji` karaoke events.
     */
    @Test fun ass_filter_drops_karaoke_and_sign_styles_keeps_dialogue() {
        val ass = assHeader + "\n" +
            ev("Default", "Kill him!") + "\n" +
            ev("OP-AJIN-Romaji", "wa") + "\n" +
            ev("Sign #7", "m 0 1 l 1 2") + "\n" +
            ev("Main Dialog", "Do you know someone named Nagai Kei?")
        val out = filterAssDialogue(ass)
        assertTrue(out.contains("Kill him!"))
        assertTrue(out.contains("Nagai Kei"))
        assertFalse(out.contains("OP-AJIN-Romaji,,"))
        assertFalse(out.contains("Sign #7,,"))
    }

    /** Drawing blocks are dropped whatever the style is called. */
    @Test fun ass_filter_drops_drawing_events_regardless_of_style() {
        val ass = assHeader + "\n" +
            ev("Default", "{\\p1}m 0 1 l 1 2 l 2 1{\\p0}") + "\n" +
            ev("Default", "Real line.")
        val out = filterAssDialogue(ass)
        assertFalse(out.contains("\\p1"))
        assertTrue(out.contains("Real line."))
    }

    /** Style names that merely start with the same letters are kept. */
    @Test fun ass_filter_keeps_lookalike_style_names() {
        val ass = assHeader + "\n" +
            ev("Editor", "An editor's note that is dialogue.") + "\n" +
            ev("Operator", "Operator speaking.") + "\n" +
            ev("Cellphone", "Text message on screen.")
        val out = filterAssDialogue(ass)
        assertTrue(out.contains("An editor's note"))
        assertTrue(out.contains("Operator speaking."))
        assertTrue(out.contains("Text message on screen."))
    }

    /**
     * Fail safe: if the rules would strip every event, the track is not
     * what we assumed. Hand back the original rather than an empty file.
     */
    @Test fun ass_filter_returns_input_when_everything_would_be_dropped() {
        val ass = assHeader + "\n" + ev("OP1-Romaji", "wa") + "\n" + ev("Sign #7", "Shop")
        assertEquals(ass, filterAssDialogue(ass))
    }

    @Test fun ass_filter_preserves_headers_and_styles_section() {
        val ass = assHeader + "\n" + ev("Default", "Hello") + "\n" + ev("Song ED2", "la la")
        val out = filterAssDialogue(ass)
        assertTrue(out.contains("[Script Info]"))
        assertTrue(out.contains("[V4+ Styles]"))
        assertTrue(out.contains("[Events]"))
        assertTrue(out.contains("Format: Layer, Start"))
    }

    // --- typesetting-dump guard (defence in depth behind selection) ---

    /** Per-character karaoke: thousands of one-letter cues. */
    @Test fun dump_guard_flags_per_character_karaoke() {
        val srt = (1..900).joinToString("\n\n") {
            "$it\n00:00:01,000 --> 00:00:02,000\n${('a' + (it % 26))}"
        }
        assertTrue(looksLikeTypesettingDump(srt))
    }

    @Test fun dump_guard_allows_occasional_short_lines() {
        val srt = (1..400).joinToString("\n\n") {
            val text = if (it % 10 == 0) "No" else "That is a perfectly ordinary line of dialogue."
            "$it\n00:0$it:01,000 --> 00:0$it:02,000\n$text"
        }
        assertFalse(looksLikeTypesettingDump(srt))
    }

    @Test fun dump_guard_flags_ass_drawing_commands() {
        val srt = (1..10).joinToString("\n\n") {
            "$it\n00:00:01,000 --> 00:00:02,000\nm 50 0 b 22 0 0 22 0 50 0 78 22 100 50 100"
        }
        assertTrue(looksLikeTypesettingDump(srt))
    }

    @Test fun dump_guard_flags_absurd_cue_counts() {
        val srt = (1..6000).joinToString("\n\n") {
            "$it\n00:00:01,000 --> 00:00:02,000\na"
        }
        assertTrue(looksLikeTypesettingDump(srt))
    }

    @Test fun dump_guard_passes_ordinary_dialogue() {
        val srt = (1..400).joinToString("\n\n") {
            "$it\n00:12:01,000 --> 00:12:02,000\nWhy did Kyo put this in my head?"
        }
        assertFalse(looksLikeTypesettingDump(srt))
    }

    // ffprobe JSON parsing → SubStream mapping (relative index assignment).
    @Test fun parse_ffprobe_assigns_relative_index_and_dispositions() {
        val json = """
            {"streams":[
              {"index":2,"codec_name":"hdmv_pgs_subtitle","disposition":{"default":1,"forced":0},"tags":{"language":"eng"}},
              {"index":3,"codec_name":"subrip","disposition":{"default":0,"forced":1},"tags":{"language":"fre","title":"Signs"}}
            ]}
        """.trimIndent()
        val parsed = FfmpegSubtitleIo.parseFfprobe(json)
        assertEquals(2, parsed.size)
        assertEquals(0, parsed[0].index) // relative, not the absolute "index":2
        assertEquals("hdmv_pgs_subtitle", parsed[0].codecName)
        assertTrue(parsed[0].default)
        assertEquals(1, parsed[1].index)
        assertEquals("fre", parsed[1].language)
        assertTrue(parsed[1].forced)
        assertEquals("Signs", parsed[1].title)
    }

    @Test fun parse_ffprobe_handles_empty_and_garbage() {
        assertTrue(FfmpegSubtitleIo.parseFfprobe("{}").isEmpty())
        assertTrue(FfmpegSubtitleIo.parseFfprobe("not json").isEmpty())
        assertTrue(FfmpegSubtitleIo.parseFfprobe("""{"streams":[]}""").isEmpty())
    }

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

    /**
     * Untagged tracks fall back to the TITLE, and the 2-letter alias "en"
     * used to be substring-matched against it. "Legendas" (Portuguese) and
     * "Slovenian" both contain "en", so both were written out as
     * <base>.en.srt — a foreign subtitle that Plex then serves as English
     * and that the ladder records as SATISFIED forever. sub-extract is
     * enabled in production, so this one was already shipping.
     */
    @Test
    fun untagged_titles_that_merely_contain_en_are_not_english() = runBlocking {
        for (title in listOf("Legendas", "Slovenian", "Legendas Completas", "SLOVENIAN")) {
            val dir = java.nio.file.Files.createTempDirectory("sub-lang-neg")
            java.nio.file.Files.writeString(dir.resolve("Ep.mkv"), "x")
            val extractor = SubtitleExtractor(
                paths = { listOf(dir.toString()) },
                langs = { listOf("en") },
                maxPerRun = { 10 },
                probe = { listOf(SubStream(index = 0, codecName = "ass", language = null, title = title)) },
                extract = { _, _, _ -> error("must not extract a non-English track titled '$title'") },
            )

            assertEquals(0, extractor.sweep().extracted, "title '$title' must not match English")
            assertFalse(java.nio.file.Files.exists(dir.resolve("Ep.en.srt")), "wrote a sidecar for '$title'")
        }
    }

    @Test
    fun untagged_english_titles_still_match() = runBlocking {
        for (title in listOf("English", "eng", "English (Full)", "Full English Dialogue", "ENGLISH")) {
            val dir = java.nio.file.Files.createTempDirectory("sub-lang-pos")
            java.nio.file.Files.writeString(dir.resolve("Ep.mkv"), "x")
            val extractor = SubtitleExtractor(
                paths = { listOf(dir.toString()) },
                langs = { listOf("en") },
                maxPerRun = { 10 },
                probe = { listOf(SubStream(index = 0, codecName = "ass", language = null, title = title)) },
                extract = { _, _, target ->
                    java.nio.file.Files.writeString(target, "1\n00:00:01,000 --> 00:00:02,000\nhi\n")
                    true
                },
            )

            assertEquals(1, extractor.sweep().extracted, "title '$title' must match English")
            assertTrue(java.nio.file.Files.exists(dir.resolve("Ep.en.srt")), "no sidecar for '$title'")
        }
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

    @Test
    fun standalone_ass_sidecar_is_converted_to_srt() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-ass-convert")
        java.nio.file.Files.writeString(dir.resolve("Ep.mkv"), "x")
        java.nio.file.Files.writeString(dir.resolve("Ep.en.ass"), "[Script Info]\n")

        var convertedFrom: String? = null
        val extractor = SubtitleExtractor(
            paths = { listOf(dir.toString()) },
            langs = { listOf("en") },
            maxPerRun = { 10 },
            // No embedded subtitle streams at all.
            probe = { emptyList() },
            extract = { _, _, _ -> error("no embedded track to extract") },
            convertSidecar = { src, target ->
                convertedFrom = src.fileName.toString()
                // The seam writes to a unique tmp path; the extractor
                // atomically renames it to the final .en.srt.
                java.nio.file.Files.writeString(target, "1\n00:00:01,000 --> 00:00:02,000\nhi\n")
                true
            },
        )

        val report = extractor.sweep()

        assertEquals(1, report.extracted)
        assertEquals("Ep.en.ass", convertedFrom, "must convert from the .ass sidecar")
        // The observable contract: a clean .en.srt on disk, no .tmp left behind.
        assertTrue(java.nio.file.Files.exists(dir.resolve("Ep.en.srt")))
        assertTrue(
            java.nio.file.Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } },
            "temp files must not be left behind",
        )
    }

    // extractOne's tmp path is now UUID-named (never the deterministic
    // "<base>.<lang2>.srt.tmp", which already races between concurrent
    // runs). Assert no .tmp survives an embedded-track extraction, mirroring
    // the leak check added for the ASS-sidecar rung above.
    @Test
    fun embedded_track_extraction_leaves_no_tmp_files() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sub-embedded-notmp")
        java.nio.file.Files.writeString(dir.resolve("Ep.mkv"), "x")

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

        assertEquals(1, report.extracted)
        assertTrue(java.nio.file.Files.exists(dir.resolve("Ep.en.srt")))
        assertTrue(
            java.nio.file.Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } },
            "temp files must not be left behind",
        )
    }

    private companion object {
        const val SAMPLE_SRT =
            "1\n00:00:01,000 --> 00:00:02,000\n<font color=\"#fff\">Hello</font> <i>world</i>\n"
    }

    private fun ms(total: Int): String {
        val h = total / 3_600_000
        val m = (total / 60_000) % 60
        val sec = (total / 1000) % 60
        val milli = total % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, sec, milli)
    }

}
