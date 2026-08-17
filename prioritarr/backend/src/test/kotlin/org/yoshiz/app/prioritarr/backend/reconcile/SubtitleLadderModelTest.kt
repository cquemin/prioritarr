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
