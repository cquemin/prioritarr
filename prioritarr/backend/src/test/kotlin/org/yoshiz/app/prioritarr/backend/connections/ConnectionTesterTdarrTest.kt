package org.yoshiz.app.prioritarr.backend.connections

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import org.yoshiz.app.prioritarr.backend.ConnectionTestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionTesterTdarrTest {
    @Test
    fun `connected when cruddb returns a json array`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/v2/cruddb") {
                    call.respondText("""[{"_id":"globalsettings","pauseAllNodes":false}]""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val port = (server as NettyApplicationEngine).resolvedConnectors().first().port
        val result = testTdarr("http://localhost:$port", apiKey = null)
        server.stop(0, 0)
        assertTrue(result.ok)
        assertEquals(ConnectionTestStatus.CONNECTED.wire, result.status)
    }

    @Test
    fun `version-failed when response is not a json array`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing { post("/api/v2/cruddb") { call.respondText("not json", ContentType.Text.Plain) } }
        }.start(wait = false)
        val port = (server as NettyApplicationEngine).resolvedConnectors().first().port
        val result = testTdarr("http://localhost:$port", apiKey = null)
        server.stop(0, 0)
        assertEquals(ConnectionTestStatus.VERSION_FAILED.wire, result.status)
    }

    @Test
    fun `connection-failed when host unreachable`() = runBlocking {
        val result = testTdarr("http://localhost:1", apiKey = null)
        assertEquals(ConnectionTestStatus.CONNECTION_FAILED.wire, result.status)
    }
}
