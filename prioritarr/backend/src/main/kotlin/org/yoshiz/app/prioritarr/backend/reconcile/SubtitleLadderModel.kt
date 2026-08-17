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
