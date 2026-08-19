package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.database.Database
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
            lastReport = null
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
