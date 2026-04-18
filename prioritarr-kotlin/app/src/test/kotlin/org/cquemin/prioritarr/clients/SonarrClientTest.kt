package org.cquemin.prioritarr.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SonarrClientTest {

    private fun clientReturning(body: String): Pair<HttpClient, MutableList<Pair<String, Map<String, List<String>>>>> {
        val calls = mutableListOf<Pair<String, Map<String, List<String>>>>()
        val engine = MockEngine { req ->
            calls += req.url.toString() to req.headers.entries().associate { it.key to it.value }
            respond(
                content = ByteReadChannel(body),
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        return http to calls
    }

    @Test
    fun `getAllSeries sends api key header and parses list`() = runTest {
        val (http, calls) = clientReturning("""[{"id":1,"title":"Attack on Titan"}]""")
        val client = SonarrClient("http://sonarr:8989", "secret", http)
        val series = client.getAllSeries()
        assertEquals(1, series.size)
        assertEquals("Attack on Titan", series[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(calls[0].first.endsWith("/api/v3/series"))
        assertEquals(listOf("secret"), calls[0].second["X-Api-Key"])
    }

    @Test
    fun `getEpisodes passes seriesId query param`() = runTest {
        val (http, calls) = clientReturning("""[{"id":99,"seriesId":1}]""")
        val client = SonarrClient("http://sonarr:8989/", "k", http)
        client.getEpisodes(1)
        assertTrue(calls[0].first.contains("seriesId=1"))
    }

    @Test
    fun `triggerSeriesSearch posts command JSON`() = runTest {
        val (http, _) = clientReturning("""{"id":42}""")
        val client = SonarrClient("http://sonarr:8989", "k", http)
        val result = client.triggerSeriesSearch(1)
        assertEquals(42, result["id"]!!.jsonPrimitive.content.toInt())
    }
}
