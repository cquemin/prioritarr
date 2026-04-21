package org.yoshiz.app.prioritarr.backend.priority

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeWatchHistoryTest {

    private fun ev(s: Int, e: Int, seconds: Long, src: String = "tautulli") =
        WatchEvent(season = s, episode = e, watchedAt = Instant.ofEpochSecond(seconds), source = src)

    @Test fun empty_input_returns_empty() {
        assertTrue(mergeWatchHistory(emptyList()).isEmpty())
    }

    @Test fun single_source_passes_through() {
        val events = listOf(ev(1, 1, 1000), ev(1, 2, 2000), ev(2, 1, 3000))
        val out = mergeWatchHistory(listOf(events))
        assertEquals(3, out.size)
    }

    @Test fun duplicate_same_source_dedups_by_season_episode() {
        // Rewatch — same (season, episode) twice from same source
        val events = listOf(ev(1, 1, 1000), ev(1, 1, 5000))
        val out = mergeWatchHistory(listOf(events))
        assertEquals(1, out.size)
        assertEquals(Instant.ofEpochSecond(5000), out[0].watchedAt) // latest wins
    }

    @Test fun duplicate_across_sources_dedups_and_keeps_latest() {
        val tautulli = listOf(ev(1, 1, 1000, "tautulli"), ev(1, 2, 2000, "tautulli"))
        val trakt = listOf(ev(1, 1, 9999, "trakt"), ev(2, 1, 3000, "trakt"))
        val out = mergeWatchHistory(listOf(tautulli, trakt)).sortedWith(compareBy({ it.season }, { it.episode }))
        assertEquals(3, out.size)

        val s1e1 = out.single { it.season == 1 && it.episode == 1 }
        assertEquals(Instant.ofEpochSecond(9999), s1e1.watchedAt)
        assertEquals("trakt", s1e1.source) // attribution = latest winner

        val s1e2 = out.single { it.season == 1 && it.episode == 2 }
        assertEquals("tautulli", s1e2.source)
    }

    @Test fun earlier_timestamp_does_not_override() {
        val tautulli = listOf(ev(1, 1, 9999, "tautulli"))
        val trakt = listOf(ev(1, 1, 1000, "trakt"))
        val out = mergeWatchHistory(listOf(tautulli, trakt))
        assertEquals(1, out.size)
        assertEquals("tautulli", out[0].source)
    }

    @Test fun one_empty_source_does_not_affect_the_other() {
        val out = mergeWatchHistory(listOf(emptyList(), listOf(ev(1, 1, 1000))))
        assertEquals(1, out.size)
    }
}
