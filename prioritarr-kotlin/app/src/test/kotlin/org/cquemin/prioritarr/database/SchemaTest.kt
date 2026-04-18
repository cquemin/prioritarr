package org.cquemin.prioritarr.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SchemaTest {

    private fun freshDb(): Db {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Db.Schema.create(driver)
        return Db(driver)
    }

    @Test
    fun `round trip priority cache`() {
        val db = freshDb()
        db.schemaQueries.upsertPriorityCache(
            series_id = 1,
            priority = 2,
            watch_pct = 0.8,
            days_since_watch = 5,
            unwatched_pending = 1,
            computed_at = "2026-04-18T10:00:00+00:00",
            expires_at = "2026-04-18T11:00:00+00:00",
            reason = "test",
        )
        val row = db.schemaQueries.selectPriorityCache(1).executeAsOneOrNull()
        assertNotNull(row)
        assertEquals(2, row.priority)
        assertEquals("test", row.reason)
    }

    @Test
    fun `dedupe returns changes=1 first time, 0 second time`() {
        val db = freshDb()
        db.schemaQueries.tryInsertDedupe("key1", "2026-04-18T10:00:00+00:00")
        val first = db.schemaQueries.dedupeChanges().executeAsOne()
        assertEquals(1, first)

        db.schemaQueries.tryInsertDedupe("key1", "2026-04-18T10:00:01+00:00")
        val second = db.schemaQueries.dedupeChanges().executeAsOne()
        assertEquals(0, second)
    }

    @Test
    fun `heartbeat upsert is idempotent`() {
        val db = freshDb()
        db.schemaQueries.upsertHeartbeat("2026-04-18T10:00:00+00:00")
        db.schemaQueries.upsertHeartbeat("2026-04-18T10:00:30+00:00")
        val ts = db.schemaQueries.selectHeartbeat().executeAsOneOrNull()
        assertEquals("2026-04-18T10:00:30+00:00", ts)
    }

    @Test
    fun `managed download list filters by client`() {
        val db = freshDb()
        db.schemaQueries.upsertManagedDownload(
            client = "qbit", client_id = "a",
            series_id = 1, episode_ids = "[1]",
            initial_priority = 1, current_priority = 1, paused_by_us = 0,
            first_seen_at = "2026-04-18T10:00:00+00:00",
            last_reconciled_at = "2026-04-18T10:00:00+00:00",
        )
        db.schemaQueries.upsertManagedDownload(
            client = "sab", client_id = "b",
            series_id = 2, episode_ids = "[2]",
            initial_priority = 3, current_priority = 3, paused_by_us = 0,
            first_seen_at = "2026-04-18T10:00:00+00:00",
            last_reconciled_at = "2026-04-18T10:00:00+00:00",
        )
        val qbit = db.schemaQueries.listManagedDownloadsByClient("qbit").executeAsList()
        assertEquals(1, qbit.size)
        assertEquals("a", qbit[0].client_id)
    }

    @Test
    fun `empty heartbeat is null`() {
        val db = freshDb()
        assertNull(db.schemaQueries.selectHeartbeat().executeAsOneOrNull())
    }
}
