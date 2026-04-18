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

class TautulliClientTest {

    private fun clientReturning(body: String): Pair<HttpClient, MutableList<String>> {
        val urls = mutableListOf<String>()
        val engine = MockEngine { req ->
            urls += req.url.toString()
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        return http to urls
    }

    @Test
    fun `getShowLibraries filters out non-show sections`() = runTest {
        val body = """
            {"response":{"result":"success","data":[
                {"section_id":"1","section_name":"TV","section_type":"show"},
                {"section_id":"2","section_name":"Movies","section_type":"movie"}
            ]}}
        """.trimIndent()
        val (http, urls) = clientReturning(body)
        val client = TautulliClient("http://tautulli:8181", "k", http)
        val libs = client.getShowLibraries()
        assertEquals(1, libs.size)
        assertEquals("TV", libs[0].jsonObject["section_name"]!!.jsonPrimitive.content)
        assertTrue(urls[0].contains("apikey=k"))
        assertTrue(urls[0].contains("cmd=get_libraries"))
    }
}
