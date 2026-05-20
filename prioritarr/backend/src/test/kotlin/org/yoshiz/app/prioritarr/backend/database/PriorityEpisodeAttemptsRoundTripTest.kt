package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

class PriorityEpisodeAttemptsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-priority-attempts", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_list_round_trip_for_p1p2() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", episodeId = 101L, lastAttemptedAt = 1_000_000L)
        val ids = db.listPriorityAttemptedSince("p1p2", 0L)
        assertEquals(listOf(101L), ids)
    }

    @Test fun upsert_then_list_round_trip_for_p3p4() {
        val db = freshDb()
        db.upsertPriorityAttempt("p3p4", episodeId = 202L, lastAttemptedAt = 1_000_000L)
        val ids = db.listPriorityAttemptedSince("p3p4", 0L)
        assertEquals(listOf(202L), ids)
    }

    @Test fun bands_are_isolated() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", 101L, 1_000_000L)
        db.upsertPriorityAttempt("p3p4", 101L, 1_000_000L)
        // Same episode_id in different bands = two separate rows
        assertEquals(listOf(101L), db.listPriorityAttemptedSince("p1p2", 0L))
        assertEquals(listOf(101L), db.listPriorityAttemptedSince("p3p4", 0L))
        db.clearPriorityAttempt("p1p2", 101L)
        assertTrue(db.listPriorityAttemptedSince("p1p2", 0L).isEmpty())
        assertEquals(listOf(101L), db.listPriorityAttemptedSince("p3p4", 0L))
    }

    @Test fun upsert_overwrites_timestamp_and_bumps_count() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", 1L, 1_000L)
        db.upsertPriorityAttempt("p1p2", 1L, 2_000L)
        db.upsertPriorityAttempt("p1p2", 1L, 3_000L)
        assertEquals(3, db.getPriorityAttemptCount("p1p2", 1L))
        assertEquals(listOf(1L), db.listPriorityAttemptedSince("p1p2", 2_500L))
    }

    @Test fun listAttemptedSince_excludes_older_rows() {
        val db = freshDb()
        db.upsertPriorityAttempt("p3p4", 1L, 1_000L)
        db.upsertPriorityAttempt("p3p4", 2L, 2_000L)
        db.upsertPriorityAttempt("p3p4", 3L, 3_000L)
        assertEquals(setOf(2L, 3L), db.listPriorityAttemptedSince("p3p4", 2_000L).toSet())
    }

    @Test fun clear_removes_a_single_episode_in_band() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", 1L, 1_000L)
        db.upsertPriorityAttempt("p1p2", 2L, 1_000L)
        db.clearPriorityAttempt("p1p2", 1L)
        assertEquals(listOf(2L), db.listPriorityAttemptedSince("p1p2", 0L))
    }

    @Test fun getPriorityAttemptCount_returns_null_for_missing() {
        val db = freshDb()
        assertNull(db.getPriorityAttemptCount("p1p2", 999L))
    }

    @Test fun migration_copies_p1p2_search_attempts_rows() {
        val tmp = Files.createTempFile("prio-migration", ".db")
        tmp.toFile().deleteOnExit()
        val path = tmp.toAbsolutePath().toString()

        val db1 = Database(path)
        db1.upsertP1P2Attempt(episodeId = 555L, lastAttemptedAt = 7_000_000L)
        db1.upsertP1P2Attempt(episodeId = 666L, lastAttemptedAt = 8_000_000L)

        // Construct a second Database against the same file — the new init
        // block re-runs the migration.
        val db2 = Database(path)
        assertEquals(setOf(555L, 666L), db2.listPriorityAttemptedSince(Database.BAND_P1P2, 0L).toSet())
        assertEquals(1, db2.getPriorityAttemptCount(Database.BAND_P1P2, 555L))
    }
}
