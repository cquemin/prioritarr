package org.yoshiz.app.prioritarr.backend.health

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.yoshiz.app.prioritarr.backend.config.Settings
import org.yoshiz.app.prioritarr.backend.database.Database
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HealthMonitorTest {

    private val tempDb: File = Files.createTempFile("health-monitor-test", ".db").toFile().also { it.deleteOnExit() }

    @AfterTest
    fun cleanup() {
        tempDb.delete()
    }

    private fun freshDb(): Database = Database(tempDb.absolutePath).also {
        // Reset between tests so test order doesn't matter.
        it.q.deleteAllProviderHealth()
    }

    private fun mockedClient(handler: (urlString: String) -> Pair<HttpStatusCode, String>): HttpClient {
        val engine = MockEngine { req ->
            val (status, body) = handler(req.url.toString())
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        return HttpClient(engine)
    }

    private fun fullySetSettings(): Settings = Settings(
        sonarrUrl = "http://sonarr:8989/sonarr",
        sonarrApiKey = "sonarr-key",
        tautulliUrl = "http://tautulli:8181",
        tautulliApiKey = "tautulli-key",
        qbitUrl = "http://vpn:6880",
        sabUrl = "http://sab:8080",
        sabApiKey = "sab-key",
        plexUrl = "http://plex:32400",
        plexToken = "plex-token",
        traktClientId = "trakt-client-id",
        traktAccessToken = "trakt-access-token",
    )

    @Test
    fun `all probes 200 result in zero unhealthy`() = runTest {
        val db = freshDb()
        val http = mockedClient { _ -> HttpStatusCode.OK to "{}" }
        val monitor = HealthMonitor(db, fullySetSettings(), http)

        val unhealthy = monitor.probeAll()

        assertEquals(0, unhealthy)
        val rows = db.listProviderHealth()
        assertEquals(6, rows.size)
        for (row in rows) {
            assertEquals("ok", row.status, "expected ok for ${row.provider}")
            assertNotNull(row.last_ok, "ok probe should bump last_ok for ${row.provider}")
        }
    }

    @Test
    fun `401 maps to unauth and detail captures status`() = runTest {
        val db = freshDb()
        // Sonarr returns 401, everything else returns 200.
        val http = mockedClient { url ->
            if (url.contains("/api/v3/system/status")) {
                HttpStatusCode.Unauthorized to "{}"
            } else {
                HttpStatusCode.OK to "{}"
            }
        }
        val monitor = HealthMonitor(db, fullySetSettings(), http)

        val unhealthy = monitor.probeAll()

        assertEquals(1, unhealthy)
        val sonarr = db.listProviderHealth().single { it.provider == "sonarr" }
        assertEquals("unauth", sonarr.status)
        assertEquals(true, sonarr.detail?.contains("401"))
    }

    @Test
    fun `unconfigured provider gets unknown status`() = runTest {
        val db = freshDb()
        // No Plex token / no Trakt creds.
        val settings = Settings(
            sonarrUrl = "http://sonarr:8989",
            sonarrApiKey = "k",
            tautulliUrl = "http://tautulli:8181",
            tautulliApiKey = "k",
            qbitUrl = "http://vpn:6880",
            sabUrl = "http://sab:8080",
            sabApiKey = "k",
            // plexUrl, plexToken, traktAccessToken intentionally unset
        )
        val http = mockedClient { _ -> HttpStatusCode.OK to "{}" }
        val monitor = HealthMonitor(db, settings, http)

        monitor.probeAll()

        val plex = db.listProviderHealth().single { it.provider == "plex" }
        val trakt = db.listProviderHealth().single { it.provider == "trakt" }
        assertEquals("unknown", plex.status)
        assertEquals("unknown", trakt.status)
        // unknown probes don't bump last_ok.
        assertNull(plex.last_ok)
    }
}
