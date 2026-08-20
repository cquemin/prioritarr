package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.streams.toList

/**
 * Eliminates Plex subtitle-burn transcodes for anime by turning
 * embedded TEXT subtitle tracks into external `.srt` sidecars.
 *
 * For each configured path we walk for video files (.mkv/.mp4). For
 * each file and each target language we:
 *
 *   1. Skip when a sidecar already exists for that language (never
 *      clobber Bazarr's downloaded subs) — see [hasSidecar].
 *   2. Probe the file's subtitle streams, keep only TEXT codecs whose
 *      `tags.language` matches the target, and pick the best dialogue
 *      track (see [selectSubStream]).
 *   3. Extract it to a temp file, strip ASS-derived `<font>` styling
 *      (see [stripFontTags]), then atomically rename to
 *      `<base>.<lang2>.srt`.
 *
 * The ffprobe/ffmpeg subprocesses are injected as [probe]/[extract]
 * seams so the decision/selection/skip/cap logic is unit-testable
 * without a real ffmpeg on the box. [FfmpegSubtitleIo] holds the real
 * `ProcessBuilder`-based implementations that Main.kt wires in.
 *
 * Nothing here is destructive to existing files: it only ever writes a
 * new sidecar next to a video, and only when none already exists.
 */
class SubtitleExtractor(
    /** Live-read list of container-absolute roots to walk. */
    private val paths: () -> List<String>,
    /** Live-read list of lang2 codes (e.g. ["en","fr"]). */
    private val langs: () -> List<String>,
    /** Live-read cap on sidecars written per run. */
    private val maxPerRun: () -> Int,
    /** ffprobe seam — returns the file's subtitle streams. */
    private val probe: suspend (Path) -> List<SubStream>,
    /**
     * ffmpeg seam — extracts the subtitle stream at the given
     * subtitle-relative index (the N in `-map 0:s:N`) to [target],
     * returning true on success.
     */
    private val extract: suspend (file: Path, streamIndex: Int, target: Path) -> Boolean,
    /**
     * ffmpeg seam converting a standalone subtitle FILE (not an embedded
     * stream) to SRT. Used when a `.ass` sidecar exists but no `.srt` —
     * free, and Plex can only soft-serve the SRT.
     */
    private val convertSidecar: suspend (src: Path, target: Path) -> Boolean = { _, _ -> false },
    /**
     * Optional seam that yields the series directories to sweep, ordered by
     * prioritarr priority (P1 first). When it returns a NON-EMPTY list the
     * sweep walks those directories IN THE GIVEN ORDER so actively-watched
     * shows get sidecars first. When null (default) or when it returns an
     * empty list, the sweep falls back to the flat filesystem-order walk of
     * [paths]. Kept impure (Sonarr + PriorityService fetch) in Main.kt so the
     * ordering core stays a pure, unit-tested function ([orderSeriesDirsByPriority]).
     */
    private val orderedDirs: (suspend () -> List<String>)? = null,
) {
    private val logger = LoggerFactory.getLogger(SubtitleExtractor::class.java)

    suspend fun sweep(): SubExtractReport {
        val report = SubExtractReport()
        val cap = maxPerRun().coerceAtLeast(0)
        val targetLangs = langs().map { it.lowercase() }

        // Priority-ordered pass: if the seam yields dirs, walk them in order so
        // the highest-priority series get served first. The shared [report] and
        // [cap] make the per-run cap bound TOTAL extractions across all dirs.
        val ordered = orderedDirs?.let {
            try {
                it()
            } catch (e: Exception) {
                logger.warn("sub-extract: ordered-dirs seam failed, falling back to flat walk: {}", e.message)
                emptyList()
            }
        }.orEmpty()

        if (ordered.isNotEmpty()) {
            for (dir in ordered) {
                if (report.capHit) break
                val dirPath = Paths.get(dir)
                if (!Files.isDirectory(dirPath)) {
                    logger.debug("sub-extract: skipping missing ordered dir {}", dirPath)
                    continue
                }
                val videos = try {
                    walkVideos(dirPath)
                } catch (e: Exception) {
                    logger.warn("sub-extract: walk failed for {}: {}", dirPath, e.message)
                    continue
                }
                for (file in videos) {
                    if (report.capHit) break
                    report.filesScanned++
                    extractInto(file, targetLangs, cap, report)
                }
            }
        } else {
            for (root in paths()) {
                if (report.capHit) break
                val rootPath = Paths.get(root)
                if (!Files.isDirectory(rootPath)) {
                    logger.debug("sub-extract: skipping missing path {}", rootPath)
                    continue
                }
                val videos = try {
                    walkVideos(rootPath)
                } catch (e: Exception) {
                    logger.warn("sub-extract: walk failed for {}: {}", rootPath, e.message)
                    continue
                }
                for (file in videos) {
                    if (report.capHit) break
                    report.filesScanned++
                    // Accumulate into the shared [report] so the cap counts total
                    // extractions across every file in the sweep, not per-file.
                    extractInto(file, targetLangs, cap, report)
                }
            }
        }
        logger.info(
            "sub-extract: scanned={} extracted={} skippedHasSidecar={} skippedNoTextTrack={} errors={} capHit={}",
            report.filesScanned, report.extracted, report.skippedHasSidecar,
            report.skippedNoTextTrack, report.errors, report.capHit,
        )
        return report
    }

    /**
     * Event-driven single-file entry point used by the Sonarr on-import
     * webhook. Runs the exact per-file logic [sweep] runs, but for just
     * [file], and returns a [SubExtractReport] scoped to that one file
     * (`filesScanned = 1`). Non-video files (extension not in [VIDEO_EXTS])
     * are a no-op. The per-run [maxPerRun] cap still applies — for a single
     * file that only matters in the pathological case of a file needing more
     * sidecars than the cap allows.
     */
    suspend fun extractForFile(file: Path): SubExtractReport {
        val report = SubExtractReport()
        val ext = file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()
        if (ext !in VIDEO_EXTS) return report
        report.filesScanned = 1
        val cap = maxPerRun().coerceAtLeast(0)
        val targetLangs = langs().map { it.lowercase() }
        extractInto(file, targetLangs, cap, report)
        return report
    }

    /**
     * Shared per-file logic: for each target lang lacking a sidecar, probe
     * → keep TEXT-codec streams matching the lang → [selectSubStream] →
     * [extractOne]. Accumulates into [report]; the [cap] is checked against
     * the running `report.extracted` so callers can share a report across
     * many files (sweep) or use a fresh one (extractForFile).
     */
    private suspend fun extractInto(
        file: Path,
        targetLangs: List<String>,
        cap: Int,
        report: SubExtractReport,
    ) {
        val base = baseName(file)
        // Languages still lacking a sidecar for this file.
        val pending = targetLangs.filter { !hasSidecar(file, it) }
        report.skippedHasSidecar += (targetLangs.size - pending.size)
        if (pending.isEmpty()) return

        for (lang2 in pending) {
            if (report.extracted >= cap) {
                report.capHit = true
                return
            }
            // Free upgrade: a standalone, language-tagged .ass sidecar converts
            // to .srt with no decode of the video at all. Try it before
            // touching ffprobe.
            //
            // Deliberately NOT falling back to a bare "$base.ass" (untagged)
            // candidate here: with no language marker there is no way to
            // know what language that .ass actually is, and mislabeling it
            // as [lang2] would relabel e.g. a Japanese .ass as an English
            // .srt — the exact class of bug (wrong-language subtitles
            // silently served as English) this whole ladder feature exists
            // to eliminate.
            for (cand in listOf("$base.$lang2.ass")) {
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
        }

        val streams = try {
            probe(file)
        } catch (e: Exception) {
            logger.warn("sub-extract: probe failed for {}: {}", file, e.message)
            report.errors++
            return
        }
        for (lang2 in pending) {
            if (report.extracted >= cap) {
                report.capHit = true
                break
            }
            val matched = streams.filter {
                matchesLang(it, lang2) && it.codecName.lowercase() in TEXT_CODECS
            }
            val chosen = selectSubStream(matched)
            if (chosen == null) {
                report.skippedNoTextTrack++
                continue
            }
            extractOne(file, lang2, chosen, report)
        }
    }

    private suspend fun extractOne(file: Path, lang2: String, stream: SubStream, report: SubExtractReport) {
        val target = sidecarTarget(file, lang2)
        // Unique name, not the deterministic "<base>.<lang2>.srt.tmp": two
        // concurrent runs (or a leftover from a crashed prior run) touching
        // the same file/lang would otherwise race on the same tmp path.
        val tmp = file.parent.resolve("${baseName(file)}.$lang2.srt.${java.util.UUID.randomUUID()}.tmp")
        val ok = try {
            extract(file, stream.index, tmp)
        } catch (e: Exception) {
            logger.warn("sub-extract: ffmpeg threw for {} (s:{}): {}", file, stream.index, e.message)
            false
        }
        if (!ok || !Files.exists(tmp)) {
            deleteQuiet(tmp)
            report.errors++
            return
        }
        try {
            // Heap guard only: a mask-layer dump reached 142 MB on
            // disk and decoding that to a String costs ~2x in heap.
            // Deliberately generous — a mixed dialogue+typesetting
            // track can be megabytes raw yet clean up to a few hundred
            // KB, and sanitizeSrt below is what rescues it.
            if (Files.size(tmp) > RAW_READ_LIMIT_BYTES) {
                logger.warn(
                    "sub-extract: discarding {} — {} bytes raw from s:{} ({}), too large to decode",
                    target, Files.size(tmp), stream.index, stream.title,
                )
                deleteQuiet(tmp)
                report.skippedNoTextTrack++
                return
            }
            // Sanitise first: a mixed dialogue+typesetting track only
            // trips the dump guard because of drawing cues, and those
            // are exactly what sanitizeSrt removes. Guarding the raw
            // text would discard tracks that clean up fine.
            val cleaned = sanitizeSrt(Files.readString(tmp))
            if (cleaned.isBlank() ||
                cleaned.length > MAX_SIDECAR_BYTES ||
                looksLikeTypesettingDump(cleaned)
            ) {
                logger.warn(
                    "sub-extract: discarding {} — s:{} ({}) looks like typesetting/karaoke, not dialogue",
                    target, stream.index, stream.title,
                )
                deleteQuiet(tmp)
                report.skippedNoTextTrack++
                return
            }
            Files.writeString(tmp, cleaned)
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"))
            } catch (_: Exception) { /* non-POSIX FS (e.g. dev on Windows) — best-effort */ }
            atomicMove(tmp, target)
            report.extracted++
            logger.info("sub-extract: wrote {} (from s:{} {})", target, stream.index, stream.codecName)
        } catch (e: Exception) {
            logger.warn("sub-extract: post-process/rename failed for {}: {}", target, e.message)
            deleteQuiet(tmp)
            report.errors++
        }
    }

    private fun walkVideos(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in VIDEO_EXTS }
                .toList()
        }

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

    private fun sidecarTarget(file: Path, lang2: String): Path =
        file.parent.resolve("${baseName(file)}.$lang2.srt")

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
        // Exact title match against any alias is always safe.
        if (title in aliases) return true
        // Substring matching is allowed ONLY for the long, unambiguous
        // spellings ("eng"/"english", "fre"/"fra"/"french") so that
        // "English (Full)" still matches. The 2-letter alias must never
        // substring-match: "en" is inside "Legendas" (Portuguese) and
        // "Slovenian", and a false positive there writes a foreign track
        // as <base>.en.srt, which the ladder then treats as SATISFIED
        // forever while Plex serves it as English - precisely the
        // wrong-language-subtitle bug this whole feature exists to
        // eliminate.
        return aliases.any { alias -> alias.length > 2 && title.contains(alias) }
    }

    private fun atomicMove(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteQuiet(p: Path) {
        try { Files.deleteIfExists(p) } catch (_: Exception) { /* best-effort */ }
    }

    private fun baseName(file: Path): String {
        val n = file.fileName.toString()
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    companion object {
        val VIDEO_EXTS = setOf("mkv", "mp4")

        /**
         * Ceiling on the SANITISED sidecar. A 24-min episode lands near
         * 30 KB and a long film under ~300 KB; 4 MB clears any real
         * subtitle while still catching a dump that survived filtering.
         */
        const val MAX_SIDECAR_BYTES = 4 * 1024 * 1024

        /**
         * Ceiling on the RAW ffmpeg output we are willing to decode.
         * Purely a heap guard, applied before [sanitizeSrt] gets a
         * chance to clean the file up.
         */
        const val RAW_READ_LIMIT_BYTES = 32L * 1024 * 1024

        /** SRT-convertible text codecs. */
        val TEXT_CODECS = setOf("ass", "ssa", "subrip", "srt", "mov_text", "webvtt", "text")

        /** Bitmap codecs that can NOT convert to SRT — excluded from selection. */
        val IMAGE_CODECS = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")

        /** lang2 -> accepted `tags.language` spellings (2/3-letter + full word). */
        val LANG_ALIASES: Map<String, Set<String>> = mapOf(
            "en" to setOf("en", "eng", "english"),
            "fr" to setOf("fr", "fre", "fra", "french"),
        )
    }
}

/**
 * ffprobe could not read the file at all (non-zero exit, or the process
 * failed to launch).
 *
 * Deliberately NOT the same as "no subtitle streams": the ladder maps a
 * probe failure to [LadderOutcome.UNREADABLE] and its long backoff,
 * while an empty stream list is a legitimate answer that sends the
 * episode on to Bazarr/Whisper.
 */
class FfmpegProbeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A series directory paired with its computed prioritarr priority (1..5, 99 = unknown). */
data class SeriesDir(val path: String, val priority: Int)

/**
 * Pure ordering core for the priority-first sweep. Given the full set of
 * Sonarr series dirs (each with its computed priority) and the configured
 * sub-extract roots, returns the series paths ordered for extraction:
 *
 *   1. Keep only series whose [SeriesDir.path] lives under one of
 *      [subExtractPaths] (prefix match: exact root, or `root + "/"` prefix).
 *   2. Sort by [SeriesDir.priority] ascending (P1 first), then by path
 *      ascending as a deterministic tie-break.
 *
 * Impure Sonarr/PriorityService fetching stays in Main.kt; this is the
 * unit-tested decision core.
 */
internal fun orderSeriesDirsByPriority(
    series: List<SeriesDir>,
    subExtractPaths: List<String>,
): List<String> {
    val roots = subExtractPaths.map { it.trimEnd('/') }
    fun underRoot(path: String): Boolean =
        roots.any { root -> path == root || path.startsWith("$root/") }
    return series
        .filter { underRoot(it.path) }
        .sortedWith(compareBy({ it.priority }, { it.path }))
        .map { it.path }
}

/** One subtitle stream, reduced to the fields selection needs. */
@Serializable
data class SubStream(
    /** Subtitle-relative index — the N in ffmpeg `-map 0:s:N`. */
    val index: Int,
    val codecName: String,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
)

/** Per-run aggregate returned to the scheduler + surfaced in job summaries. */
@Serializable
data class SubExtractReport(
    var filesScanned: Int = 0,
    var extracted: Int = 0,
    var skippedHasSidecar: Int = 0,
    var skippedNoTextTrack: Int = 0,
    var errors: Int = 0,
    /** True when [SubtitleExtractor.maxPerRun] stopped the run early. */
    var capHit: Boolean = false,
)

/**
 * Pick the best dialogue track out of already-filtered [candidates].
 *
 * Signs/songs tracks are discarded FIRST, before any disposition is
 * consulted, and are never used as a last resort:
 *
 *  - They hold typesetting and karaoke, not dialogue. Converted to SRT
 *    they yield tens of thousands of junk cues -- ASS vector drawing
 *    commands (`m 50 0 b 22 0 ...`) and per-syllable karaoke -- one
 *    library file reached 726,939 lines / 142 MB.
 *  - Releases DO flag them `default`. Checking `default` first (as this
 *    did originally) picked `[FFF] Signs` over `[FFF] Full Subtitles`
 *    in 52 of 302 bad sidecars found in the library on 2026-08-20.
 *  - Falling back to `candidates.first()` picked a signs track for files
 *    that ship nothing else. That junk sidecar then satisfied the
 *    ladder's "has .en.srt" check, so the episode never climbed to
 *    Bazarr or Whisper. Returning null lets the ladder do its job.
 *
 * Among the surviving dialogue tracks: prefer `default`, then non-forced,
 * else the first. An untitled track is assumed to be dialogue.
 */
internal fun selectSubStream(candidates: List<SubStream>): SubStream? {
    val dialogue = candidates.filterNot { isSignsSongs(it.title) }
    if (dialogue.isEmpty()) return null
    dialogue.firstOrNull { it.default }?.let { return it }
    dialogue.firstOrNull { !it.forced }?.let { return it }
    return dialogue.first()
}

/**
 * Clean an ffmpeg-converted SRT: strip styling, drop cues that aren't
 * dialogue, renumber what survives.
 *
 * Track selection alone can't fix this. Many fansub "Full Subtitles"
 * tracks carry dialogue AND typesetting in one stream -- Assassination
 * Classroom S02E05 has 354 dialogue events against 12,886 `\p1` vector
 * drawing events. ffmpeg has no notion of an ASS drawing block, so it
 * emits the raw drawing commands as cue text:
 *
 *     m 50 0 b 22 0 0 22 0 50 0 78 22 100 50 100
 *
 * Dropping those took that episode from 15,943 cues / 2.7 MB to 3,098,
 * with every line of dialogue intact.
 *
 * A cue is dropped when, after tag stripping, it is blank or is a pure
 * drawing command. The drawing test deliberately requires a leading
 * `m <x> <y>` move AND a body of nothing but drawing tokens, so ordinary
 * numeric dialogue survives: `555-0199 555-0123` and `1997, 1998, 1999`
 * are kept.
 */
internal fun sanitizeSrt(raw: String): String {
    val blocks = raw.replace("\r\n", "\n").trim().split(BLANK_LINE_REGEX)
    val kept = ArrayList<String>(blocks.size)
    for (block in blocks) {
        val lines = block.split("\n").filter { it.isNotBlank() }
        val timingAt = lines.indexOfFirst { it.contains(CUE_ARROW) }
        if (timingAt < 0) continue
        val timing = lines[timingAt]
        val text = stripFontTags(lines.drop(timingAt + 1).joinToString("\n")).trim()
        if (text.isEmpty() || isDrawingCue(text)) continue
        kept += "${kept.size + 1}\n$timing\n$text"
    }
    return if (kept.isEmpty()) "" else kept.joinToString("\n\n") + "\n"
}

/**
 * Is this cue text an ASS vector drawing rather than dialogue? Requires
 * both a leading move command and a body containing only drawing tokens.
 */
internal fun isDrawingCue(text: String): Boolean =
    text.length >= MIN_DRAWING_LEN &&
        DRAW_START_REGEX.containsMatchIn(text) &&
        DRAW_BODY_REGEX.matches(text)

private const val MIN_DRAWING_LEN = 12
private val BLANK_LINE_REGEX = Regex("""\n\s*\n""")

/** A leading ASS move command: `m 50 0 ` or `m -12.5 8,`. */
private val DRAW_START_REGEX = Regex("""^m -?\d+(\.\d+)? -?\d+(\.\d+)?[ ,]""")

/** Drawing verbs, coordinates and separators — nothing else. */
private val DRAW_BODY_REGEX = Regex("""^[mlbspcn\d\s.,-]+$""")

/**
 * Defence in depth behind [selectSubStream]: does this SRT look like a
 * typesetting/karaoke dump rather than dialogue?
 *
 * Selection is title-based, and titles lie. This catches the output
 * itself on two signals no real subtitle track produces:
 *
 *  - ASS vector drawing commands surviving as cue text (`m 12 34 l ...`,
 *    `m 12 34 b ...`). Five or more means we extracted a mask layer.
 *  - An absurd cue count. A 2-hour film sits near 2,000; the junk files
 *    ranged from 88,000 to 181,000. 5,000 clears any real subtitle by a
 *    wide margin.
 */
internal fun looksLikeTypesettingDump(srt: String): Boolean {
    if (DRAW_CMD_REGEX.findAll(srt).take(DRAW_CMD_LIMIT).count() >= DRAW_CMD_LIMIT) return true
    if (countCues(srt) > MAX_PLAUSIBLE_CUES) return true
    return isPerCharacterKaraoke(srt)
}

/**
 * Per-character karaoke emits one cue per syllable-letter, filling the
 * file with one- and two-character cues. Real dialogue has occasional
 * short lines ("No", "Yeah!") but never in this proportion. This is what
 * finally caught 86 - Eighty Six S01E14, which had no drawing commands
 * and sat under the cue ceiling at 4,463.
 */
private fun isPerCharacterKaraoke(srt: String): Boolean {
    val texts = cueTexts(srt)
    if (texts.size < MIN_CUES_FOR_KARAOKE_CHECK) return false
    val tiny = texts.count { it.length <= TINY_CUE_LEN }
    return tiny.toDouble() / texts.size >= TINY_CUE_RATIO
}

/** Cue text bodies, tags stripped, blanks dropped. */
private fun cueTexts(srt: String): List<String> {
    val out = ArrayList<String>()
    for (block in srt.trim().split(BLANK_LINE_REGEX)) {
        val lines = block.split("\n").filter { it.isNotBlank() }
        val at = lines.indexOfFirst { it.contains(CUE_ARROW) }
        if (at < 0) continue
        val text = stripFontTags(lines.drop(at + 1).joinToString(" ")).trim()
        if (text.isNotEmpty()) out += text
    }
    return out
}

private const val MIN_CUES_FOR_KARAOKE_CHECK = 200
private const val TINY_CUE_LEN = 2
private const val TINY_CUE_RATIO = 0.30

private fun countCues(srt: String): Int {
    var n = 0
    var i = srt.indexOf(CUE_ARROW)
    while (i >= 0) {
        n++
        i = srt.indexOf(CUE_ARROW, i + CUE_ARROW.length)
    }
    return n
}

private const val CUE_ARROW = "-->"
private const val DRAW_CMD_LIMIT = 5
private const val MAX_PLAUSIBLE_CUES = 5_000

/** An ASS drawing command at the start of a cue line: `m 50 0 b 22 0 ...`. */
private val DRAW_CMD_REGEX = Regex("""^m -?\d+ -?\d+ [lbm] """, RegexOption.MULTILINE)

private fun isSignsSongs(title: String?): Boolean {
    val t = title?.lowercase() ?: return false
    return t.contains("sign") || t.contains("song")
}

/**
 * Strip ASS-derived `<font ...>` / `</font>` styling from SRT text
 * while keeping the bold/italic/underline tags Plex renders. Pure so
 * it can be unit-tested in isolation.
 */
internal fun stripFontTags(input: String): String =
    input.replace(FONT_TAG_REGEX, "").replace(ASS_OVERRIDE_REGEX, "")

private val FONT_TAG_REGEX = Regex("</?font[^>]*>", RegexOption.IGNORE_CASE)

/**
 * ASS inline override blocks that leak into ffmpeg's SRT output, e.g.
 * `{\an8}` (position), `{\i1}`, `{\pos(1,2)}`, and ASS drawing/clip
 * markers like `{=146}`. The `{=` form was missed originally, letting
 * 86 - Eighty Six S01E14 through as 4,463 cues of `{=146}d`, `o`, `n`.
 * Only blocks that start with
 * `{\` are stripped, so ordinary text containing braces is preserved.
 */
private val ASS_OVERRIDE_REGEX = Regex("""\{[\\=][^}]*}""")

/**
 * Real ffprobe/ffmpeg seams. Kept out of [SubtitleExtractor] so the
 * reconciler stays subprocess-free (and thus unit-testable). Wired into
 * the reconciler from Main.kt. There is no existing ProcessBuilder
 * precedent in this codebase — this is the one place we shell out.
 */
object FfmpegSubtitleIo {
    private val logger = LoggerFactory.getLogger(FfmpegSubtitleIo::class.java)

    /**
     * List the subtitle streams of [file] via ffprobe (JSON).
     *
     * THROWS [FfmpegProbeException] when ffprobe itself fails, rather
     * than returning an empty list. An empty list is a real, meaningful
     * answer ("this file has no subtitle streams"), and conflating the
     * two made [LadderOutcome.UNREADABLE] unreachable in production: a
     * corrupt file probed as "no embedded track", climbed to Whisper,
     * failed there, and was filed as UPSTREAM_DOWN with a 1-hour retry
     * instead of UNREADABLE with its long backoff.
     */
    suspend fun probe(file: Path): List<SubStream> = withContext(Dispatchers.IO) {
        val cmd = listOf(
            "ffprobe", "-v", "error",
            "-select_streams", "s",
            "-show_entries", "stream=index,codec_name:stream_tags=language,title:stream_disposition=default,forced",
            "-of", "json",
            file.toString(),
        )
        try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText() // drain stderr
            val code = proc.waitFor()
            if (code != 0) {
                logger.warn("sub-extract: ffprobe exit {} for {}: {}", code, file, err.take(300))
                throw FfmpegProbeException("ffprobe exit $code for $file")
            }
            parseFfprobe(out)
        } catch (e: FfmpegProbeException) {
            throw e
        } catch (e: Exception) {
            logger.warn("sub-extract: ffprobe launch failed for {}: {}", file, e.message)
            throw FfmpegProbeException("ffprobe launch failed for $file: ${e.message}", e)
        }
    }

    /** Extract subtitle stream `0:s:[streamIndex]` from [file] into [target] as SRT. */
    suspend fun extract(file: Path, streamIndex: Int, target: Path): Boolean = withContext(Dispatchers.IO) {
        val cmd = listOf(
            "ffmpeg", "-v", "error", "-y",
            "-i", file.toString(),
            "-map", "0:s:$streamIndex",
            "-c:s", "srt",
            // Force the SRT muxer explicitly: the temp target ends in
            // ".srt.tmp", and ffmpeg would otherwise try to infer the format
            // from the ".tmp" extension and fail ("Unable to choose an output
            // format").
            "-f", "srt",
            target.toString(),
        )
        try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                logger.warn("sub-extract: ffmpeg exit {} for {} (s:{}): {}", code, file, streamIndex, log.take(300))
                false
            } else {
                Files.exists(target)
            }
        } catch (e: Exception) {
            logger.warn("sub-extract: ffmpeg launch failed for {}: {}", file, e.message)
            false
        }
    }

    /**
     * Convert a standalone subtitle file to SRT. `-f srt` is mandatory:
     * the temp target ends `.tmp`, so ffmpeg cannot infer the format and
     * fails with "Unable to choose an output format".
     */
    suspend fun convertSubtitleFile(src: Path, target: Path): Boolean = withContext(Dispatchers.IO) {
        val cmd = listOf("ffmpeg", "-v", "error", "-y", "-i", src.toString(), "-f", "srt", target.toString())
        try {
            // DISCARD rather than merge-and-never-read: nothing consumes
            // the merged stream here, so a chatty failure would fill the
            // pipe buffer and hang until the 5-minute timeout. Same shape
            // of bug as FfmpegAudioIo.extractWav had.
            val p = ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!p.waitFor(5, TimeUnit.MINUTES)) { p.destroyForcibly(); return@withContext false }
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parse ffprobe's `-select_streams s -of json` output into
     * [SubStream]s. The subtitle-relative index is the position in the
     * returned list (ffprobe enumerates subtitle streams in file order,
     * matching ffmpeg's `0:s:N` numbering). Internal for unit testing.
     */
    internal fun parseFfprobe(json: String): List<SubStream> {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val streams = (root["streams"] as? JsonArray) ?: return emptyList()
        return streams.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val codec = o["codec_name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val tags = o["tags"] as? JsonObject
            val disp = o["disposition"] as? JsonObject
            SubStream(
                index = i,
                codecName = codec,
                language = tags?.get("language")?.jsonPrimitive?.contentOrNull,
                title = tags?.get("title")?.jsonPrimitive?.contentOrNull,
                default = (disp?.get("default")?.jsonPrimitive?.intOrNull ?: 0) == 1,
                forced = (disp?.get("forced")?.jsonPrimitive?.intOrNull ?: 0) == 1,
            )
        }
    }
}
