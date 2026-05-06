package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrSeasonSearchTest {

    @Test fun triggerSeasonSearch_posts_command_with_seriesId_and_seasonNumber() = runBlocking {
        var capturedBody: String? = null
        var capturedPath: String? = null
        val engine = MockEngine { req ->
            capturedPath = req.url.encodedPath
            capturedBody = req.body.toByteArray().toString(Charsets.UTF_8)
            respond(
                content = ByteReadChannel("""{"id": 123, "status": "queued"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val sonarr = SonarrClient("http://sonarr:8989", "key", client)

        val resp = sonarr.triggerSeasonSearch(seriesId = 7L, seasonNumber = 3)

        assertEquals(123L, resp["id"]!!.jsonPrimitive.content.toLong())
        assertEquals("/api/v3/command", capturedPath)
        val parsed = Json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("SeasonSearch", parsed["name"]!!.jsonPrimitive.content)
        assertEquals(7L, parsed["seriesId"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, parsed["seasonNumber"]!!.jsonPrimitive.content.toInt())
    }
}
