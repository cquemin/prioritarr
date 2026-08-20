package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleLadderStateTest {

    private fun freshDb(): Database {
        val tmp = java.nio.file.Files.createTempFile("prio-ladder-test", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test
    fun round_trips_and_upserts() {
        val db = freshDb()
        assertNull(db.getLadderState(42L))

        db.upsertLadderState(42L, "R2_BAZARR", "NO_SOURCE", 1L, "2026-09-01T00:00:00Z")
        val first = db.getLadderState(42L)!!
        assertEquals("R2_BAZARR", first.last_rung)
        assertEquals(1L, first.attempts)

        db.upsertLadderState(42L, "R3_WHISPER", "SATISFIED", 1L, null)
        val second = db.getLadderState(42L)!!
        assertEquals("R3_WHISPER", second.last_rung)
        assertEquals("SATISFIED", second.outcome)
        assertNull(second.next_retry_at)
    }

    @Test
    fun due_query_excludes_future_retries_and_respects_limit() {
        val db = freshDb()
        db.upsertLadderState(1L, "R2_BAZARR", "NO_SOURCE", 1L, "2026-01-01T00:00:00Z") // past
        db.upsertLadderState(2L, "R2_BAZARR", "NO_SOURCE", 1L, "2099-01-01T00:00:00Z") // future
        db.upsertLadderState(3L, "R2_BAZARR", "NO_SOURCE", 1L, null)                   // never tried again

        val due = db.ladderEpisodesDue(now = "2026-06-01T00:00:00Z", limit = 10L)
        val ids = due.map { it.episode_id }.toSet()

        assertTrue(1L in ids, "past retry time is due")
        assertTrue(3L in ids, "null retry time is due")
        assertTrue(2L !in ids, "future retry time is not due")

        assertEquals(1, db.ladderEpisodesDue("2026-06-01T00:00:00Z", limit = 1L).size)
    }
}
