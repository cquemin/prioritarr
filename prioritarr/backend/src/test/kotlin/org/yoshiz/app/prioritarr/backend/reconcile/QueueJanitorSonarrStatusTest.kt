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
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueJanitorSonarrStatusTest {
    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-janitor-status", ".db"); tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun mockHttp() = HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) {
        install(ContentNegotiation) { json() }
    }
    private inner class FakeQbit : QBitClient(baseUrl = "http://fake", http = mockHttp()) {
        override suspend fun getTorrents(category: String?): JsonArray = JsonArray(emptyList())
    }
    private inner class FakeSab : SABClient(baseUrl = "http://fake", apiKey = "x", http = mockHttp()) {
        override suspend fun getQueue(): JsonArray = JsonArray(emptyList())
        override suspend fun getHistory(limit: Int): JsonArray = JsonArray(emptyList())
    }
    private inner class FakeSonarr(private val queue: JsonArray) : SonarrClient(baseUrl = "http://fake", apiKey = "x", http = mockHttp()) {
        override suspend fun getQueue(pageSize: Int): JsonArray = queue
    }
    private fun queueRow(downloadId: String, status: String): JsonObject = buildJsonObject {
        put("downloadId", JsonPrimitive(downloadId))
        put("trackedDownloadStatus", JsonPrimitive(status))
    }
    private fun janitor(db: Database, sonarr: SonarrClient) =
        QueueJanitor(sonarr = sonarr, qbit = FakeQbit(), sab = FakeSab(), db = db)

    @Test fun p1_with_sonarr_warning_status_is_stuck_immediately() = runTest {
        val db = freshDb()
        db.upsertManagedDownload("qbit", "ccc", 1L, listOf(11L), 1L, 1L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        val report = janitor(db, FakeSonarr(buildJsonArray { add(queueRow("CCC", "warning")) })).sweep(dryRun = true)
        assertEquals(1, report.scanned)
    }

    @Test fun p1_with_ok_status_is_not_stuck() = runTest {
        val db = freshDb()
        db.upsertManagedDownload("qbit", "ddd", 1L, listOf(11L), 1L, 1L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        val report = janitor(db, FakeSonarr(buildJsonArray { add(queueRow("DDD", "ok")) })).sweep(dryRun = true)
        assertEquals(0, report.scanned)
    }

    @Test fun non_p1_with_warning_status_is_ignored() = runTest {
        val db = freshDb()
        db.upsertManagedDownload("qbit", "eee", 2L, listOf(21L), 3L, 3L, false, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
        val report = janitor(db, FakeSonarr(buildJsonArray { add(queueRow("EEE", "warning")) })).sweep(dryRun = true)
        assertEquals(0, report.scanned)
    }
}
