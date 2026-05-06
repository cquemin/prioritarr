package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.nio.file.Files

class P5AttemptsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prioritarr-p5-test", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_select_round_trip() {
        val db = freshDb()
        db.upsertP5Attempt(
            seriesId = 42L,
            seasonNumber = 1,
            lastAttemptedAt = 1_000_000L,
            lastMissingCount = 7,
            consecutiveEmptyAttempts = 0,
        )

        val row = db.getP5Attempt(42L, 1)
        assertNotNull(row)
        assertEquals(42L, row.seriesId)
        assertEquals(1, row.seasonNumber)
        assertEquals(1_000_000L, row.lastAttemptedAt)
        assertEquals(7, row.lastMissingCount)
        assertEquals(0, row.consecutiveEmptyAttempts)
    }

    @Test fun upsert_overwrites_on_conflict() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, 5, 0)
        db.upsertP5Attempt(1L, 1, 2_000L, 3, 1)
        val row = db.getP5Attempt(1L, 1)!!
        assertEquals(2_000L, row.lastAttemptedAt)
        assertEquals(3, row.lastMissingCount)
        assertEquals(1, row.consecutiveEmptyAttempts)
    }

    @Test fun null_last_missing_count_round_trips() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, null, 0)
        val row = db.getP5Attempt(1L, 1)!!
        assertNull(row.lastMissingCount)
    }

    @Test fun list_for_series_returns_only_that_series() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, 5, 0)
        db.upsertP5Attempt(1L, 2, 1_100L, 3, 0)
        db.upsertP5Attempt(2L, 1, 1_200L, 8, 0)

        val seriesOne = db.listP5AttemptsForSeries(1L)
        assertEquals(2, seriesOne.size)
        assertEquals(setOf(1, 2), seriesOne.map { it.seasonNumber }.toSet())
    }

    @Test fun delete_for_series_removes_only_that_series() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, 5, 0)
        db.upsertP5Attempt(2L, 1, 1_200L, 8, 0)
        db.deleteP5AttemptsForSeries(1L)
        assertEquals(0, db.listP5AttemptsForSeries(1L).size)
        assertEquals(1, db.listP5AttemptsForSeries(2L).size)
    }

    @Test fun managed_downloads_season_number_set_get() {
        val db = freshDb()
        db.upsertManagedDownload(
            client = "qbit",
            clientId = "abc",
            seriesId = 99L,
            episodeIds = listOf(1L, 2L),
            initialPriority = 5L,
            currentPriority = 5L,
            pausedByUs = false,
            firstSeenAt = "2026-05-05T00:00:00+00:00",
            lastReconciledAt = "2026-05-05T00:00:00+00:00",
        )
        db.setManagedSeasonNumber("qbit", "abc", 3)

        val row = db.getManagedDownload("qbit", "abc")
        assertNotNull(row)
        assertEquals(3L, row.season_number)
    }
}
