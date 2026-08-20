package org.yoshiz.app.prioritarr.backend

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
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.Intervals
import org.yoshiz.app.prioritarr.backend.config.Settings
import org.yoshiz.app.prioritarr.backend.database.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [buildLadderCandidates] is the only thing bounding the sub-ladder's
 * fan-out against Sonarr. An unbounded version of this scan is what
 * starved Sonarr's SQLite for seven hours on 2026-08-14 (a total
 * download outage, with every health check green), so the bound is
 * asserted here directly rather than trusted.
 *
 * The point of interest is the number of `/api/v3/episode` calls: that
 * is the expensive query (Sonarr joins the episode-file table for
 * `includeEpisodeFile=true`), and it must stay at
 * `subLadderMaxSeriesPerSweep` per sweep even when the whole library is
 * caught up and the episode budget therefore never fills.
 */
class LadderCandidatesTest {

    private val root = "/storage/media/video/anime"

    /** Sonarr over MockEngine, recording every episode-list call. */
    private class FakeSonarr(seriesCount: Int, private val episodesPerSeries: Int) {
        /** seriesId of each `/api/v3/episode` call, in order. */
        val episodeCalls = mutableListOf<String>()

        private val seriesJson = (1..seriesCount).joinToString(",", "[", "]") { id ->
            """{"id":$id,"path":"/storage/media/video/anime/Show $id"}"""
        }

        private fun episodesJson(seriesId: String) = (1..episodesPerSeries).joinToString(",", "[", "]") { n ->
            val epId = seriesId.toLong() * 1000 + n
            """{"id":$epId,"seriesId":$seriesId,"hasFile":true,""" +
                """"episodeFile":{"path":"/storage/media/video/anime/Show $seriesId/E0$n.mkv"}}"""
        }

        val client: SonarrClient

        init {
            val engine = MockEngine { req ->
                val body = when (req.url.encodedPath) {
                    "/api/v3/series" -> seriesJson
                    "/api/v3/episode" -> {
                        val id = req.url.parameters["seriesId"]!!
                        episodeCalls += id
                        episodesJson(id)
                    }
                    else -> error("unexpected call: ${req.url}")
                }
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            client = SonarrClient(
                "http://sonarr:8989",
                "k",
                HttpClient(engine) { install(ContentNegotiation) { json() } },
            )
        }
    }

    private fun freshDb(): Database {
        val tmp = java.nio.file.Files.createTempFile("prio-ladder-cand", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private fun settings(maxSeries: Int = 3, maxEpisodes: Int = 5) = Settings(
        sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
        qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
        subExtractPaths = listOf(root),
        intervals = Intervals(
            subLadderMaxSeriesPerSweep = maxSeries,
            subLadderMaxPerSweep = maxEpisodes,
        ),
    )

    /** Park every episode of every series far in the future. */
    private fun parkAll(db: Database, seriesCount: Int, episodesPerSeries: Int) {
        for (s in 1..seriesCount) {
            for (n in 1..episodesPerSeries) {
                db.upsertLadderState(s * 1000L + n, "R0_SATISFIED", "SATISFIED", 0L, "2099-01-01T00:00:00+00:00")
            }
        }
    }

    @Test
    fun a_caught_up_library_still_costs_only_max_series_episode_calls() = runTest {
        val sonarr = FakeSonarr(seriesCount = 20, episodesPerSeries = 2)
        val db = freshDb()
        parkAll(db, seriesCount = 20, episodesPerSeries = 2)

        val out = buildLadderCandidates(sonarr.client, db, settings(maxSeries = 3), AtomicInteger(0))

        assertTrue(out.isEmpty(), "nothing is due, so nothing may be scheduled")
        // The episode budget never fills when everything is satisfied, so
        // only the series cap can bound the scan. 3, not 20.
        assertEquals(3, sonarr.episodeCalls.size)
    }

    @Test
    fun the_cursor_advances_across_sweeps_instead_of_rescanning_the_top_n() = runTest {
        val sonarr = FakeSonarr(seriesCount = 20, episodesPerSeries = 2)
        val db = freshDb()
        parkAll(db, seriesCount = 20, episodesPerSeries = 2)
        val cursor = AtomicInteger(0)
        val s = settings(maxSeries = 3)

        buildLadderCandidates(sonarr.client, db, s, cursor)
        buildLadderCandidates(sonarr.client, db, s, cursor)

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), sonarr.episodeCalls)
    }

    @Test
    fun the_cursor_wraps_at_the_end_of_the_series_list() = runTest {
        // 5 series, 3 per sweep: the second sweep runs off the end and must
        // wrap to the front rather than scanning nothing (or throwing).
        val sonarr = FakeSonarr(seriesCount = 5, episodesPerSeries = 2)
        val db = freshDb()
        parkAll(db, seriesCount = 5, episodesPerSeries = 2)
        val cursor = AtomicInteger(0)
        val s = settings(maxSeries = 3)

        buildLadderCandidates(sonarr.client, db, s, cursor)
        buildLadderCandidates(sonarr.client, db, s, cursor)
        buildLadderCandidates(sonarr.client, db, s, cursor)

        assertEquals(
            listOf("1", "2", "3", "4", "5", "1", "2", "3", "4"),
            sonarr.episodeCalls,
        )
    }

    @Test
    fun due_episodes_are_returned_and_capped_by_the_episode_budget() = runTest {
        val sonarr = FakeSonarr(seriesCount = 20, episodesPerSeries = 4)
        val db = freshDb() // nothing parked: every episode is due

        val out = buildLadderCandidates(
            sonarr.client, db, settings(maxSeries = 3, maxEpisodes = 5), AtomicInteger(0),
        )

        assertEquals(5, out.size, "the episode budget must cap the batch")
        assertEquals(listOf(1001L, 1002L, 1003L, 1004L, 2001L), out.map { it.episodeId })
        // Budget filled on the second series, so the third was never queried.
        assertEquals(2, sonarr.episodeCalls.size)
    }

    @Test
    fun no_configured_roots_means_no_candidates_and_no_sonarr_calls() = runTest {
        val sonarr = FakeSonarr(seriesCount = 5, episodesPerSeries = 2)
        val db = freshDb()

        val out = buildLadderCandidates(
            sonarr.client,
            db,
            settings().copy(subExtractPaths = emptyList()),
            AtomicInteger(0),
        )

        assertTrue(out.isEmpty())
        assertTrue(sonarr.episodeCalls.isEmpty())
    }
}
