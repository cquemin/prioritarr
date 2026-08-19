package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.yoshiz.app.prioritarr.backend.auth.apiKey

/**
 * The route this exercises requires the `api_key` auth provider
 * (see [subtitleLadderRoutes]'s KDoc for why: it triggers a Whisper
 * transcription run for a caller-supplied episode id, and bypasses
 * the Plex/congestion gate by design). Every test here therefore
 * installs [Authentication] first, mirroring the harness in
 * `auth/ApiKeyAuthTest.kt`.
 */
class SubLadderRouteTest {

    @Test
    fun posting_an_episode_id_runs_the_ladder_once() = testApplication {
        var ranFor: Long? = null
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = "secret" }
            }
            subtitleLadderRoutes(
                enabled = { true },
                runLadderFor = { episodeId -> ranFor = episodeId; "SATISFIED" },
            )
        }

        val resp = client.post("/api/v2/subtitles/ladder/25749") {
            header("X-Api-Key", "secret")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue("SATISFIED" in resp.bodyAsText())
        assertEquals(25749L, ranFor)
    }

    @Test
    fun unknown_episode_is_404() = testApplication {
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = "secret" }
            }
            subtitleLadderRoutes(enabled = { true }, runLadderFor = { null })
        }

        val resp = client.post("/api/v2/subtitles/ladder/999") {
            header("X-Api-Key", "secret")
        }

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun non_numeric_episode_id_is_400() = testApplication {
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = "secret" }
            }
            subtitleLadderRoutes(enabled = { true }, runLadderFor = { "SATISFIED" })
        }

        val resp = client.post("/api/v2/subtitles/ladder/not-a-number") {
            header("X-Api-Key", "secret")
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun missing_api_key_is_401() = testApplication {
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = "secret" }
            }
            subtitleLadderRoutes(enabled = { true }, runLadderFor = { "SATISFIED" })
        }

        val resp = client.post("/api/v2/subtitles/ladder/25749")

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun disabled_feature_is_503_and_runs_nothing() = testApplication {
        // "Ships disabled" has to hold for the HTTP surface too: this route
        // bypasses the Plex/congestion gate by design, so while the feature
        // is off it must not be able to start a Whisper run at all.
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = "secret" }
            }
            subtitleLadderRoutes(
                enabled = { false },
                runLadderFor = { error("must not run while the ladder is disabled") },
            )
        }

        val resp = client.post("/api/v2/subtitles/ladder/25749") {
            header("X-Api-Key", "secret")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
    }

    @Test
    fun a_second_concurrent_run_is_rejected_not_stacked() = testApplication {
        // Only R3 is serialised inside the ladder, so N concurrent POSTs
        // would otherwise spawn N concurrent ffprobe/ffmpeg processes on a
        // box that also serves live Plex transcodes.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = "secret" }
            }
            subtitleLadderRoutes(enabled = { true }) {
                concurrent.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "SATISFIED"
            }
        }

        coroutineScope {
            val first = async {
                client.post("/api/v2/subtitles/ladder/1") { header("X-Api-Key", "secret") }
            }
            entered.await()

            val second = client.post("/api/v2/subtitles/ladder/2") { header("X-Api-Key", "secret") }
            assertEquals(HttpStatusCode.TooManyRequests, second.status)

            release.complete(Unit)
            assertEquals(HttpStatusCode.OK, first.await().status)
        }
        assertEquals(1, concurrent.get(), "the second request must not have started a run")
    }
}
