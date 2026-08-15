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
import java.time.Instant
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

    /**
     * The probe is a single cheap call, so it sails through a rate limit
     * that is failing hundreds of real history fetches. Reporting "ok"
     * there hides an outage that is actively corrupting priorities.
     */
    @Test
    fun trakt_is_not_ok_while_the_rate_limit_breaker_is_open() = runTest {
        val db = freshDb()
        val http = mockedClient { _ -> HttpStatusCode.OK to "{}" }
        val settings = fullySetSettings()
        val until = Instant.now().plusSeconds(120)
        val monitor = HealthMonitor(db, { settings }, http, traktRateLimitedUntil = { until })

        monitor.probeAll()

        val trakt = db.listProviderHealth().single { it.provider == "trakt" }
        assertEquals("unreachable", trakt.status)
        assertEquals(true, trakt.detail?.contains("rate limited"))
    }

    @Test
    fun trakt_is_ok_once_the_breaker_has_closed() = runTest {
        val db = freshDb()
        val http = mockedClient { _ -> HttpStatusCode.OK to "{}" }
        val settings = fullySetSettings()
        val monitor = HealthMonitor(db, { settings }, http, traktRateLimitedUntil = { null })

        monitor.probeAll()

        val trakt = db.listProviderHealth().single { it.provider == "trakt" }
        assertEquals("ok", trakt.status)
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

    /** Mock variant that also exposes the Authorization header — needed
     *  to assert that a refreshed Trakt token is what actually goes on
     *  the wire on the next probe pass. */
    private fun mockedClientWithAuth(handler: (urlString: String, auth: String?) -> Pair<HttpStatusCode, String>): HttpClient {
        val engine = MockEngine { req ->
            val (status, body) = handler(req.url.toString(), req.headers["Authorization"])
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
        val s = fullySetSettings()
        val monitor = HealthMonitor(db, { s }, http)

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
        val s = fullySetSettings()
        val monitor = HealthMonitor(db, { s }, http)

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
        val monitor = HealthMonitor(db, { settings }, http)

        monitor.probeAll()

        val plex = db.listProviderHealth().single { it.provider == "plex" }
        val trakt = db.listProviderHealth().single { it.provider == "trakt" }
        assertEquals("unknown", plex.status)
        assertEquals("unknown", trakt.status)
        // unknown probes don't bump last_ok.
        assertNull(plex.last_ok)
    }

    @Test
    fun `trakt token refreshed between passes is picked up without restart`() = runTest {
        // Regression for the stale-snapshot bug: probeAll() must re-read
        // the settings provider each tick so a Trakt access_token minted
        // by the runtime refresh flow (persisted to the DB override, not
        // the boot Settings) flips the banner from unauth -> ok on the
        // next 5-minute pass instead of waiting for a container restart.
        val db = freshDb()
        // Mock: Trakt 200 only when the bearer token matches "new-token".
        val http = mockedClientWithAuth { url, auth ->
            when {
                url.contains("api.trakt.tv") ->
                    if (auth == "Bearer new-token") HttpStatusCode.OK to "{}"
                    else HttpStatusCode.Unauthorized to "{}"
                else -> HttpStatusCode.OK to "{}"
            }
        }
        var token = "stale-token"
        val monitor = HealthMonitor(db, { fullySetSettings().copy(traktAccessToken = token) }, http)

        // First pass: stale token rejected by Trakt mock.
        monitor.probeAll()
        assertEquals("unauth", db.listProviderHealth().single { it.provider == "trakt" }.status)

        // Simulate a runtime refresh writing a new access_token to the DB
        // override — the provider lambda now returns it.
        token = "new-token"

        // Second pass: provider re-read picks up the new token live and
        // the trakt probe goes green.
        monitor.probeAll()
        assertEquals("ok", db.listProviderHealth().single { it.provider == "trakt" }.status)
    }
}
