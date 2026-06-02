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

class QueueJanitorP1ThresholdTest {
    // QueueJanitor uses Instant.now() as its clock, so "idle for 40 min"
    // must be expressed relative to the real current time: 40 min ago.
    // That is > the 30-min P1 threshold but well under the 48h flat one.
    private val idle40minEpoch = java.time.Instant.now().epochSecond - 40 * 60

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-janitor-p1", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun mockHttp() = HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) {
        install(ContentNegotiation) { json() }
    }
    private inner class FakeQbit(private val torrents: JsonArray) :
        QBitClient(baseUrl = "http://fake", http = mockHttp()) {
        override suspend fun getTorrents(category: String?): JsonArray = torrents
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
    private fun janitor(db: Database, qbit: QBitClient) = QueueJanitor(
        sonarr = FakeSonarr(), qbit = qbit, sab = FakeSab(), db = db,
        stuckAfter = Duration.ofHours(48), p1StuckAfter = Duration.ofMinutes(30),
    )

    @Test fun p1_torrent_idle_40min_is_stuck() = runTest {
        val db = freshDb()
        db.upsertManagedDownload("qbit", "AAA", 1L, listOf(11L), 1L, 1L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        val report = janitor(db, FakeQbit(buildJsonArray { add(torrent("AAA", idle40minEpoch)) })).sweep(dryRun = true)
        assertEquals(1, report.scanned)
    }

    @Test fun non_p1_torrent_idle_40min_is_not_stuck() = runTest {
        val db = freshDb()
        db.upsertManagedDownload("qbit", "BBB", 2L, listOf(21L), 4L, 4L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        val report = janitor(db, FakeQbit(buildJsonArray { add(torrent("BBB", idle40minEpoch)) })).sweep(dryRun = true)
        assertEquals(0, report.scanned)
    }
}
