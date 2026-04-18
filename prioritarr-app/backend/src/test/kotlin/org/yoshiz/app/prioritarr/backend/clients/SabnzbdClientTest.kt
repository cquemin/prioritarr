package org.yoshiz.app.prioritarr.backend.clients

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SabnzbdClientTest {

    @Test
    fun `PRIORITY_MAP matches python`() {
        assertEquals(2, SABClient.PRIORITY_MAP[1])
        assertEquals(1, SABClient.PRIORITY_MAP[2])
        assertEquals(0, SABClient.PRIORITY_MAP[3])
        assertEquals(-1, SABClient.PRIORITY_MAP[4])
        assertEquals(-1, SABClient.PRIORITY_MAP[5])
    }

    @Test
    fun `getQueue extracts slots from nested wrapper`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { req ->
            urls += req.url.toString()
            respond(
                ByteReadChannel("""{"queue":{"slots":[{"nzo_id":"a","priority":"2"}]}}"""),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client = SABClient("http://sab:8080", "k", http)
        val slots = client.getQueue()
        assertEquals(1, slots.size)
        assertEquals("a", slots[0].jsonObject["nzo_id"]!!.jsonPrimitive.content)
        assertTrue(urls[0].contains("mode=queue"))
        assertTrue(urls[0].contains("apikey=k"))
        assertTrue(urls[0].contains("output=json"))
    }
}
