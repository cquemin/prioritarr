package org.yoshiz.app.prioritarr.backend.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.yoshiz.app.prioritarr.backend.clients.PlexClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossSourceSyncTest {

    @Test
    fun `syncSeries resolves the plex key by series id, not by title`() = runTest {
        // Sonarr title differs from any Plex title; only the id mapping
        // (233 -> 85175) can resolve the Plex side. A title lookup would
        // null out and the series would be wrongly skipped.
        val seriesBody = """{"id":233,"title":"Mismatched Sonarr Title","tvdbId":305089}"""
        val sonarr = SonarrClient(
            "http://sonarr", "k",
            HttpClient(MockEngine {
                respond(
                    ByteReadChannel(seriesBody),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }) { install(ContentNegotiation) { json() } },
        )

        val plexPaths = mutableListOf<String>()
        val plex = PlexClient(
            "http://plex:32400", "tok",
            HttpClient(MockEngine { req ->
                plexPaths += req.url.encodedPath
                respond(ByteReadChannel("<MediaContainer/>"), HttpStatusCode.OK)
            }),
        )

        val mappings = MappingState().apply { inject("85175", 233L) }
        val sync = CrossSourceSync(sonarr, plex, trakt = null, mappings = mappings)

        val report = sync.syncSeries(233L, dryRun = true, direction = SyncDirection.BOTH)

        assertEquals(null, report.skippedReason, "series must not be skipped — its id maps to a Plex key")
        assertTrue(
            plexPaths.any { it.contains("/library/metadata/85175/allLeaves") },
            "expected Plex queried under id-resolved key 85175, got $plexPaths",
        )
    }
}
