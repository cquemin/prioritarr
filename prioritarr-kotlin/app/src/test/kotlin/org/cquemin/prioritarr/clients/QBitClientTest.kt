package org.cquemin.prioritarr.clients

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

class QBitClientTest {

    @Test
    fun `getTorrents parses json list`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { req ->
            urls += req.url.toString()
            respond(
                ByteReadChannel("""[{"hash":"abc","name":"t1"}]"""),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client = QBitClient("http://vpn:8080", http = http)
        val torrents = client.getTorrents()
        assertEquals(1, torrents.size)
        assertEquals("abc", torrents[0].jsonObject["hash"]!!.jsonPrimitive.content)
        assertTrue(urls[0].endsWith("/api/v2/torrents/info"))
    }
}
