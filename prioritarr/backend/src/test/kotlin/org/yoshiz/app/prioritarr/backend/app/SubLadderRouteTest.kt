package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubLadderRouteTest {

    @Test
    fun posting_an_episode_id_runs_the_ladder_once() = testApplication {
        var ranFor: Long? = null
        application {
            subtitleLadderRoutes(
                runLadderFor = { episodeId -> ranFor = episodeId; "SATISFIED" },
            )
        }

        val resp = client.post("/api/v2/subtitles/ladder/25749")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue("SATISFIED" in resp.bodyAsText())
        assertEquals(25749L, ranFor)
    }

    @Test
    fun unknown_episode_is_404() = testApplication {
        application {
            subtitleLadderRoutes(runLadderFor = { null })
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/v2/subtitles/ladder/999").status)
    }
}
