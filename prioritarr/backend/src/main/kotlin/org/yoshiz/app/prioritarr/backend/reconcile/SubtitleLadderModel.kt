package org.yoshiz.app.prioritarr.backend.reconcile

import java.time.Duration

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

/**
 * Does this sidecar's content look like an actual episode of dialogue?
 *
 * The existence of `<base>.en.srt` is not enough. Re:Zero S02E10 on
 * 2026-08-22: Bazarr found an "English" subtitle on AnimeTosho, scored
 * it 87.22%, synced it — and it was a signs-only track, 45 bytes and a
 * single cue reading "I Know Hell", the episode title card. Nothing
 * upstream can tell that apart: to Bazarr it is a well-matched English
 * subtitle, and to the ladder it was SATISFIED, so Whisper never ran and
 * Plex would have shown one line of text for a 24-minute episode.
 *
 * The bar is deliberately low. A real episode subtitle runs to hundreds
 * of cues; anything under [MIN_PLAUSIBLE_CUES] is a signs track, a title
 * card, or a truncated download, and none of those is worth blocking
 * the rest of the ladder for.
 */
fun isPlausibleSubtitle(content: String): Boolean =
    countCueArrows(content) >= MIN_PLAUSIBLE_CUES

private fun countCueArrows(text: String): Int {
    var n = 0
    var i = text.indexOf("-->")
    while (i >= 0) {
        n++
        i = text.indexOf("-->", i + 3)
    }
    return n
}

/**
 * Fewest cues a full-episode subtitle can plausibly have. A 24-minute
 * anime episode runs 250-600; even a near-silent one clears 20.
 */
const val MIN_PLAUSIBLE_CUES = 20

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
 * [r1AlreadyTried] is the same guard one rung lower. R1 is picked purely
 * from "an embedded English text track exists", which is a property of
 * the file and therefore never changes: an episode whose extraction
 * always fails (broken track, ffmpeg error, or an extraction that only
 * produced a French sidecar) would otherwise cycle
 * R1 -> NO_SOURCE -> backoff -> R1 forever and never reach Bazarr or
 * Whisper. The caller derives it from the persisted rung, comparing on
 * ORDER rather than equality: an episode currently sitting on R2/R3/R4
 * has demonstrably already had its R1 turn, and equality alone would let
 * it fall back to R1 on the next sweep and oscillate R1 <-> R2 forever.
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
    r1AlreadyTried: Boolean = false,
): Rung {
    if (sidecar == SidecarState.SATISFIED) return Rung.R0_SATISFIED
    // R1 is free, so it runs even when a variant is present — but only
    // once: see [r1AlreadyTried].
    if (hasEmbeddedEnglishText && !r1AlreadyTried) return Rung.R1_EMBEDDED
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
 * Consecutive [LadderOutcome.UPSTREAM_DOWN]s after which an episode stops
 * being retried on the flat 1-hour outage cadence and falls onto the
 * normal [backoffFor] curve (which also starts consuming attempts).
 *
 * 5 because the outage retry is hourly: five consecutive failures means
 * the upstream has been unreachable for at least ~5 hours. Every restart
 * or redeploy of Bazarr/Whisper on this box completes in seconds to
 * minutes, so past that point the failure is no longer plausibly
 * transient — it is a misconfiguration (the motivating case: `whisperUrl`
 * pointing at a container that does not exist, where every retry pays a
 * full ffmpeg audio decode, gets connection-refused, and reports a green
 * job with zero subtitles produced, forever).
 *
 * Not lower: a genuinely transient outage must still not consume the
 * retry budget, which is the whole point of [consumesAttempt]. Five
 * hourly retries is far more headroom than any real outage here needs.
 * Not higher: past ~5 hours the churn is pure waste — CPU, disk reads,
 * and a permanently growing pool of "due" episodes that crowds out real
 * work.
 */
const val UPSTREAM_DOWN_ESCALATE_AFTER: Int = 5

/**
 * Has this episode seen enough consecutive [LadderOutcome.UPSTREAM_DOWN]s
 * that the outage should stop being treated as transient?
 *
 * [streak] counts CONSECUTIVE outage outcomes: any other outcome resets
 * it to zero, so a real blip never accumulates towards escalation.
 */
fun upstreamDownExhausted(streak: Long): Boolean = streak >= UPSTREAM_DOWN_ESCALATE_AFTER

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
 * @param congested true when Sonarr's search queue is congested, i.e.
 *   `SearchQueueControl.isCongested()` — the count of ALL in-flight
 *   search commands (of every type, not just P1/P2) is at or above
 *   `searchCongestionThreshold`.
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
