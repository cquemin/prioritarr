package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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

/**
 * Persisted position, reduced to what [SubtitleLadder] needs.
 *
 * [upstreamDowns] counts CONSECUTIVE [LadderOutcome.UPSTREAM_DOWN]
 * outcomes; any other outcome resets it to 0. It is what stops a
 * permanently unreachable Bazarr/Whisper from being retried hourly
 * forever - see [UPSTREAM_DOWN_ESCALATE_AFTER].
 */
data class LadderState(val lastRung: String, val attempts: Long, val upstreamDowns: Long = 0)

/**
 * Per-sweep aggregate surfaced in the job summary.
 *
 * Plain [Int]s: every counter is only ever mutated from within the single
 * call to [SubtitleLadder.sweep] that owns this instance (passed explicitly
 * into [SubtitleLadder.runOne] as a parameter, never as shared mutable
 * state), so there is nothing concurrent here to defend against.
 */
data class LadderReport(
    var considered: Int = 0,
    var satisfied: Int = 0,
    var extracted: Int = 0,
    var bazarrTriggered: Int = 0,
    var whispered: Int = 0,
    var noSource: Int = 0,
    var upstreamDown: Int = 0,
    var skippedGate: Int = 0,
    /**
     * True when the gate closed PART WAY through the batch and the
     * remaining candidates were dropped. Distinct from a sweep that never
     * started: this one means work was already in progress.
     */
    var gateAborted: Boolean = false,
    /**
     * Why the gate last closed, or null if it never did. Surfaced in the
     * job summary: without it a permanently-closed gate (e.g. no Plex
     * configured, so the probe always fails and the gate always fails
     * closed) is indistinguishable in the UI from "nothing was due".
     */
    var gateReason: String? = null,
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
    private val saveState: (
        episodeId: Long,
        rung: String,
        outcome: String,
        attempts: Long,
        nextRetryAt: String?,
        upstreamDowns: Long,
    ) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(SubtitleLadder::class.java)

    /** Carried across ticks to debounce Plex's mid-playback zero reports. */
    private val idleTicks = AtomicInteger(0)

    /** Global Whisper mutex — one transcription at a time, process-wide. */
    private val whisperSlot = Mutex()

    suspend fun sweep(): LadderReport {
        val report = LadderReport()

        val gate = evaluateGate()
        if (!gate.open) {
            report.skippedGate = 1
            report.gateReason = gate.reason
            logger.debug("sub-ladder: standing down - {}", gate.reason)
            return report
        }

        val batch = candidates().take(maxPerSweep().coerceAtLeast(0))
        for (c in batch) {
            // Re-evaluated before EVERY candidate, not once per sweep. A
            // single R3 candidate runs for tens of minutes, so five of them
            // is a sweep spanning 03:00 -> 06:20; a gate decided at 03:00
            // says nothing about 03:10, and a stream starting then would
            // compete with hours of Whisper - the exact workload this gate
            // exists to keep off the CPU.
            //
            // This is also the mandatory pre-R3 check: the rung for a
            // candidate is only chosen inside runOne, so gating here gates
            // every rung, R3 included. An R3 run already in flight cannot be
            // preempted (neither the ffmpeg decode nor the Whisper call is
            // interruptible mid-episode), so the guarantee is "at most one
            // episode of Whisper overlaps a new stream", not zero.
            val g = evaluateGate()
            if (!g.open) {
                report.skippedGate++
                report.gateReason = g.reason
                report.gateAborted = true
                logger.info(
                    "sub-ladder: gate closed mid-sweep after {} candidate(s), dropping {} - {}",
                    report.considered, batch.size - report.considered, g.reason,
                )
                break
            }
            report.considered++
            try {
                when (runOne(c, report)) {
                    LadderOutcome.SATISFIED -> report.satisfied++
                    LadderOutcome.NO_SOURCE -> report.noSource++
                    LadderOutcome.UPSTREAM_DOWN -> report.upstreamDown++
                    LadderOutcome.UNREADABLE -> report.noSource++
                    LadderOutcome.BLOCKED_VARIANT -> {}
                }
            } catch (e: CancellationException) {
                // Shutdown. MUST propagate: CancellationException is an
                // Exception, so the catch below would otherwise swallow it
                // and the loop would carry on to the next candidate - making
                // a multi-hour sweep impossible to interrupt.
                throw e
            } catch (e: Exception) {
                // One candidate's DB hiccup (SQLite BUSY is realistic on
                // this box) must not abort the rest of the batch.
                logger.warn("sub-ladder: candidate {} failed unexpectedly: {}", c.episodeId, e.message)
                report.noSource++
            }
        }
        logger.info(
            "sub-ladder: considered={} satisfied={} extracted={} bazarr={} whisper={} noSource={} " +
                "upstreamDown={} gateAborted={}",
            report.considered, report.satisfied, report.extracted,
            report.bazarrTriggered, report.whispered, report.noSource, report.upstreamDown,
            report.gateAborted,
        )
        return report
    }

    /**
     * Evaluate the Plex/congestion gate and carry the debounce counter
     * into the next evaluation.
     *
     * Cheap by construction: [plexSessions] is one call to Plex and
     * [congested] one to Sonarr's command queue. Two requests per
     * candidate is nothing against tens of minutes of work per candidate.
     */
    private suspend fun evaluateGate(): LadderGate {
        val gate = decideLadderGate(
            plexSessions = try { plexSessions() } catch (_: Exception) { null },
            congested = try { congested() } catch (_: Exception) { false },
            idleTicks = idleTicks.get(),
            resumeAfterIdleTicks = resumeAfterIdleTicks(),
        )
        idleTicks.set(gate.idleTicks)
        return gate
    }

    /**
     * Climb one episode by exactly one rung and record the outcome.
     *
     * One rung per call on purpose: R2 is asynchronous inside Bazarr, so
     * the result only exists on a later sweep. Persisting `lastRung` is
     * what lets the next pass know Bazarr has already had its turn and
     * move on to Whisper.
     *
     * [report], when supplied by [sweep], is where rung counters get
     * attributed. It is passed as a plain parameter rather than kept as
     * shared mutable state, so concurrent on-demand calls (outside any
     * sweep) and overlapping sweeps can never misattribute each other's
     * counters.
     */
    suspend fun runOne(candidate: LadderCandidate, report: LadderReport? = null): LadderOutcome {
        // Loaded once up front (rather than again inside record()) since
        // both the bazarrAlreadyTried check and every record() call need
        // it: one DB read per candidate instead of two.
        val prior = loadState(candidate.episodeId)

        val file = candidate.videoPath
        val dir = file.parent ?: return record(candidate, Rung.R4_EXHAUSTED, LadderOutcome.UNREADABLE, prior)
        val base = baseNameOf(file)
        val sidecar = classifySidecars(siblingNames(dir), base)

        if (sidecar == SidecarState.SATISFIED) {
            return record(candidate, Rung.R0_SATISFIED, LadderOutcome.SATISFIED, prior)
        }

        val embedded = try {
            hasEmbeddedEnglishText(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("sub-ladder: probe failed for {}: {}", file, e.message)
            return record(candidate, Rung.R1_EMBEDDED, LadderOutcome.UNREADABLE, prior)
        }

        val rung = nextRung(
            sidecar = sidecar,
            hasEmbeddedEnglishText = embedded,
            priority = candidate.priority,
            whisperMaxPriority = whisperMaxPriority(),
            whisperEnabled = whisperEnabled(),
            bazarrAlreadyTried = prior?.lastRung == Rung.R2_BAZARR.name,
            // Compared on rung ORDER, not equality: an episode parked on
            // R2/R3/R4 has demonstrably already had its R1 turn, and an
            // equality check would let a file whose English track never
            // extracts drop back to R1 on the very next sweep and oscillate
            // R1 <-> R2 forever without ever reaching Whisper.
            r1AlreadyTried = reachedAtLeast(prior, Rung.R1_EMBEDDED),
        )

        return when (rung) {
            Rung.R0_SATISFIED -> record(candidate, rung, LadderOutcome.SATISFIED, prior)

            Rung.R1_EMBEDDED -> {
                val ok = try {
                    extractEmbedded(file)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
                if (ok) report?.let { it.extracted++ }
                record(candidate, rung, if (ok) LadderOutcome.SATISFIED else LadderOutcome.NO_SOURCE, prior)
            }

            Rung.R2_BAZARR -> {
                val ok = try {
                    triggerBazarr(candidate.seriesId, candidate.episodeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { false }
                if (ok) report?.let { it.bazarrTriggered++ }
                // Bazarr searches asynchronously: a successful trigger only
                // means "queued". The result, if any, is seen next sweep.
                record(candidate, rung, if (ok) LadderOutcome.NO_SOURCE else LadderOutcome.UPSTREAM_DOWN, prior)
            }

            Rung.R3_WHISPER -> whisperSlot.withLock {
                val srt = try {
                    whisperTranslate(file, candidate.audioLang)
                } catch (e: CancellationException) {
                    // Shutdown must not be recorded as an upstream outage:
                    // that would persist a bogus row and, worse, make the
                    // cancellation invisible to the caller.
                    throw e
                } catch (e: Exception) {
                    logger.warn("sub-ladder: whisper failed for {}: {}", file, e.message)
                    null
                }
                if (srt.isNullOrBlank()) {
                    record(candidate, rung, LadderOutcome.UPSTREAM_DOWN, prior)
                } else {
                    report?.let { it.whispered++ }
                    // Either we wrote it, or Bazarr beat us to it mid-run.
                    // Both mean the episode now has its .en.srt.
                    writeSidecarIfAbsent(dir, base, srt)
                    record(candidate, rung, LadderOutcome.SATISFIED, prior)
                }
            }

            Rung.R4_EXHAUSTED -> {
                val outcome =
                    if (sidecar == SidecarState.VARIANT_ONLY) LadderOutcome.BLOCKED_VARIANT
                    else LadderOutcome.NO_SOURCE
                record(candidate, rung, outcome, prior)
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

    /** Has this episode already been at [rung] or beyond? */
    private fun reachedAtLeast(prior: LadderState?, rung: Rung): Boolean {
        val last = prior?.lastRung ?: return false
        val parsed = try { Rung.valueOf(last) } catch (_: IllegalArgumentException) { return false }
        return parsed.ordinal >= rung.ordinal
    }

    private fun record(c: LadderCandidate, rung: Rung, outcome: LadderOutcome, prior: LadderState?): LadderOutcome {
        val priorAttempts = prior?.attempts ?: 0L
        // Consecutive outage count; ANY other outcome resets it, so only a
        // sustained (no longer plausibly transient) outage escalates.
        val upstreamDowns =
            if (outcome == LadderOutcome.UPSTREAM_DOWN) (prior?.upstreamDowns ?: 0L) + 1 else 0L
        // Past the threshold the episode stops getting the flat 1-hour
        // outage retry and joins the normal backoff curve - which also
        // means it finally starts consuming attempts. Without this, a
        // misconfigured upstream (whisperUrl pointing at a container that
        // does not exist) churns every whisper-eligible episode through a
        // full ffmpeg audio decode every hour, forever, while the job
        // reports green and produces zero subtitles.
        val escalated = upstreamDownExhausted(upstreamDowns)
        val attempts =
            if (consumesAttempt(outcome) || escalated) priorAttempts + 1 else priorAttempts
        if (escalated) {
            logger.warn(
                "sub-ladder: episode {} has {} consecutive upstream failures at {} - " +
                    "escalating to the normal backoff curve (attempts={})",
                c.episodeId, upstreamDowns, rung.name, attempts,
            )
        }
        val nextRetryAt = if (escalated) isoPlus(backoffFor(attempts.toInt())) else when (outcome) {
            // NOT null: null means "due now" (ladderEpisodesDue selects
            // next_retry_at IS NULL OR next_retry_at <= ?), so a satisfied
            // episode would be re-selected, and re-confirmed, on every
            // single sweep, burning the whole per-sweep budget on episodes
            // that already have nothing to do. A week gives cheap
            // self-healing if the sidecar is later deleted or replaced.
            LadderOutcome.SATISFIED -> isoPlus(java.time.Duration.ofDays(7))
            // Retry an outage soon; it is our fault, not the file's.
            LadderOutcome.UPSTREAM_DOWN -> isoPlus(java.time.Duration.ofHours(1))
            // A variant sidecar blocks R2/R3 until someone resolves it by
            // hand; 28d is not a real retry cadence, just a cap on how
            // often we re-verify the variant is still there.
            LadderOutcome.BLOCKED_VARIANT -> isoPlus(java.time.Duration.ofDays(28))
            else -> isoPlus(backoffFor(attempts.toInt()))
        }
        saveState(c.episodeId, rung.name, outcome.name, attempts, nextRetryAt, upstreamDowns)
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
