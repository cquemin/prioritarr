package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.testing.testApplication
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
            subtitleLadderRoutes(runLadderFor = { null })
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
            subtitleLadderRoutes(runLadderFor = { "SATISFIED" })
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
            subtitleLadderRoutes(runLadderFor = { "SATISFIED" })
        }

        val resp = client.post("/api/v2/subtitles/ladder/25749")

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
