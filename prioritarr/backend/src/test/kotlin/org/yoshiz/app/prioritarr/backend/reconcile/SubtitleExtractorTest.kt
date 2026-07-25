package org.yoshiz.app.prioritarr.backend.reconcile

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

    // (b') the language-less <base>.srt also blocks extraction.
    @Test fun plain_srt_sidecar_blocks_all_langs() = runTest {
        val root = tempRoot()
        touch(root, "Show.S01E03.mkv")
        touch(root, "Show.S01E03.srt", "plain")
        val streams = listOf(SubStream(index = 0, codecName = "subrip", language = "eng"))
        val rec = ExtractRecorder()
        val report = extractor(root, streams, rec, langs = listOf("en", "fr")).sweep()

        assertEquals(0, rec.calls.size)
        assertEquals(2, report.skippedHasSidecar) // both langs blocked
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

    @Test fun strip_removes_ass_override_blocks_keeps_plain_braces() {
        assertEquals("Top text styled end", stripFontTags("{\\an8}Top text {\\i1}styled{\\i0} end"))
        // Braces that aren't ASS overrides (no leading backslash) are preserved.
        assertEquals("{not an override}", stripFontTags("{not an override}"))
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

    @Test fun select_prefers_default_first() {
        val s = selectSubStream(
            listOf(
                SubStream(0, "ass", "eng", "Dialogue"),
                SubStream(1, "ass", "eng", "Signs", default = true),
            ),
        )
        assertEquals(1, s?.index)
    }

    @Test fun select_falls_back_to_first_when_all_forced_signs() {
        val s = selectSubStream(
            listOf(
                SubStream(0, "ass", "eng", "Signs", forced = true),
                SubStream(1, "ass", "eng", "Songs", forced = true),
            ),
        )
        assertEquals(0, s?.index)
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

    private companion object {
        const val SAMPLE_SRT =
            "1\n00:00:01,000 --> 00:00:02,000\n<font color=\"#fff\">Hello</font> <i>world</i>\n"
    }
}
