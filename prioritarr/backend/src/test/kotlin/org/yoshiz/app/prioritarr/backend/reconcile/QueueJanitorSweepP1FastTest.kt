package org.yoshiz.app.prioritarr.backend.reconcile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueJanitorSweepP1FastTest {
    // genuinely 40 minutes ago (sweep reads Instant.now(); no injectable clock)
    private val idle40minEpoch = java.time.Instant.now().epochSecond - 40 * 60

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-janitor-fast", ".db"); tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun mockHttp() = HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) {
        install(ContentNegotiation) { json() }
    }
    private inner class FakeQbit(private val t: JsonArray) : QBitClient(baseUrl = "http://fake", http = mockHttp()) {
        override suspend fun getTorrents(category: String?): JsonArray = t
    }
    private inner class FakeSab : SABClient(baseUrl = "http://fake", apiKey = "x", http = mockHttp()) {
        override suspend fun getQueue(): JsonArray = JsonArray(emptyList())
        override suspend fun getHistory(limit: Int): JsonArray = JsonArray(emptyList())
    }
    private inner class FakeSonarr : SonarrClient(baseUrl = "http://fake", apiKey = "x", http = mockHttp()) {
        override suspend fun getQueue(pageSize: Int): JsonArray = JsonArray(emptyList())
    }
    private fun torrent(hash: String, lastActivity: Long): JsonObject = buildJsonObject {
        put("hash", JsonPrimitive(hash)); put("last_activity", JsonPrimitive(lastActivity))
        put("state", JsonPrimitive("downloading")); put("content_path", JsonPrimitive("/nope"))
    }

    @Test fun fast_pass_scans_only_p1_items() = runTest {
        val db = freshDb()
        db.upsertManagedDownload("qbit", "P1H", 1L, listOf(11L), 1L, 1L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        db.upsertManagedDownload("qbit", "P4H", 2L, listOf(21L), 4L, 4L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        val janitor = QueueJanitor(
            sonarr = FakeSonarr(),
            qbit = FakeQbit(buildJsonArray { add(torrent("P1H", idle40minEpoch)); add(torrent("P4H", idle40minEpoch)) }),
            sab = FakeSab(), db = db,
            stuckAfter = Duration.ofHours(48), p1StuckAfter = Duration.ofMinutes(30),
        )
        // Both torrents are idle 40min. P1 (>30min) is stuck; P4 (<48h) is not.
        // sweepP1Fast must scan exactly the P1 one.
        assertEquals(1, janitor.sweepP1Fast(dryRun = true).scanned)
    }

    @Test fun fast_pass_remediates_untracked_p1_grab_from_sonarr_queue() = runTest {
        val db = freshDb()
        // Series 200 computes to P1 (priority cache), but the grab was a
        // Sonarr RSS auto-grab — there is NO managed_downloads row for it.
        // The janitor must still remediate it off the cached priority.
        db.upsertPriorityCache(
            seriesId = 200L, priority = 1L, watchPct = null, daysSinceWatch = null,
            unwatchedPending = null, computedAt = "2024-01-01T00:00:00Z",
            expiresAt = "2999-01-01T00:00:00Z", reason = "P1",
        )
        val stuckEntry = buildJsonObject {
            put("trackedDownloadStatus", JsonPrimitive("warning"))
            put("downloadId", JsonPrimitive("ABC-NZO"))
            put("downloadClient", JsonPrimitive("SABnzbd"))
            put("seriesId", JsonPrimitive(200))
            put("episodeId", JsonPrimitive(27703))
        }
        val sonarr = object : SonarrClient("http://fake", "x", mockHttp()) {
            override suspend fun getQueue(pageSize: Int): JsonArray = buildJsonArray { add(stuckEntry) }
        }
        val janitor = QueueJanitor(
            sonarr = sonarr, qbit = FakeQbit(JsonArray(emptyList())), sab = FakeSab(), db = db,
            stuckAfter = Duration.ofHours(48), p1StuckAfter = Duration.ofMinutes(30),
        )
        assertEquals(1, janitor.sweepP1Fast(dryRun = true).scanned)
    }
}
