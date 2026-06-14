package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrCommandsTest {
    @Test fun getCommands_parses_command_array() = runTest {
        val body = """
          [{"id":1,"name":"SeriesSearch","status":"started","started":"2026-06-14T01:39:15Z"},
           {"id":2,"name":"RssSync","status":"queued"}]
        """.trimIndent()
        val engine = MockEngine { req ->
            assertEquals("/api/v3/command", req.url.encodedPath)
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val sonarr = SonarrClient("http://sonarr:8989", "k", http)

        val arr = sonarr.getCommands()

        assertEquals(2, arr.size)
        assertEquals("SeriesSearch", arr[0].jsonObject["name"]!!.jsonPrimitive.contentOrNull)
        assertEquals("started", arr[0].jsonObject["status"]!!.jsonPrimitive.contentOrNull)
    }

    @Test fun restartApp_posts_to_restart_endpoint() = runTest {
        var hitPath: String? = null
        val engine = MockEngine { req ->
            hitPath = req.url.encodedPath
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val sonarr = SonarrClient("http://sonarr:8989", "k", http)

        sonarr.restartApp()

        assertEquals("/api/v3/system/restart", hitPath)
    }
}
