package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.test.runTest
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
    ) = SubtitleExtractor(
        paths = { listOf(root.toString()) },
        langs = { langs },
        maxPerRun = { maxPerRun },
        probe = { streams },
        extract = recorder.seam,
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
