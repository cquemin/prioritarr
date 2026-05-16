package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files

class P1P2AttemptsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prioritarr-p1p2-test", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_list_round_trip() {
        val db = freshDb()
        db.upsertP1P2Attempt(episodeId = 101L, lastAttemptedAt = 1_000_000L)
        val ids = db.listP1P2AttemptedSince(0L)
        assertEquals(listOf(101L), ids)
    }

    @Test fun upsert_overwrites_timestamp_and_bumps_count() {
        val db = freshDb()
        db.upsertP1P2Attempt(episodeId = 1L, lastAttemptedAt = 1_000L)
        assertEquals(1, db.getP1P2AttemptCount(1L))
        db.upsertP1P2Attempt(episodeId = 1L, lastAttemptedAt = 2_000L)
        assertEquals(2, db.getP1P2AttemptCount(1L))
        db.upsertP1P2Attempt(episodeId = 1L, lastAttemptedAt = 3_000L)
        assertEquals(3, db.getP1P2AttemptCount(1L))
        // Both rows must collapse to a single episode with the later timestamp.
        assertEquals(listOf(1L), db.listP1P2AttemptedSince(2_500L))
        assertEquals(listOf(1L), db.listP1P2AttemptedSince(0L))
    }

    @Test fun listAttemptedSince_excludes_older_rows() {
        val db = freshDb()
        db.upsertP1P2Attempt(1L, 1_000L)
        db.upsertP1P2Attempt(2L, 2_000L)
        db.upsertP1P2Attempt(3L, 3_000L)
        assertEquals(setOf(2L, 3L), db.listP1P2AttemptedSince(2_000L).toSet())
    }

    @Test fun clear_removes_a_single_episode() {
        val db = freshDb()
        db.upsertP1P2Attempt(1L, 1_000L)
        db.upsertP1P2Attempt(2L, 1_000L)
        db.clearP1P2Attempt(1L)
        assertEquals(listOf(2L), db.listP1P2AttemptedSince(0L))
    }
}
