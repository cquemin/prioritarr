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
