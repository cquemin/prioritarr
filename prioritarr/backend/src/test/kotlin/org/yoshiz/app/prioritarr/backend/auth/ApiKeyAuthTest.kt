package org.yoshiz.app.prioritarr.backend.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiKeyAuthTest {

    private fun harness(expected: String?, test: suspend (client: io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                apiKey("api_key") { expectedKey = expected }
            }
            routing {
                authenticate("api_key") {
                    route("/api/v2") {
                        get("/ping") { call.respondText("pong") }
                    }
                }
            }
        }
        test(client)
    }

    @Test
    fun `rejects request with no header when key required`() = harness("secret") { c ->
        val resp = c.get("/api/v2/ping")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue("\"type\":\"/errors/unauthorized\"" in resp.bodyAsText())
    }

    @Test
    fun `rejects wrong key`() = harness("secret") { c ->
        val resp = c.get("/api/v2/ping") { header("X-Api-Key", "wrong") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `accepts X-Api-Key header`() = harness("secret") { c ->
        val resp = c.get("/api/v2/ping") { header("X-Api-Key", "secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("pong", resp.bodyAsText())
    }

    @Test
    fun `accepts Authorization Bearer header`() = harness("secret") { c ->
        val resp = c.get("/api/v2/ping") { header("Authorization", "Bearer secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `no expected key disables auth`() = harness(null) { c ->
        val resp = c.get("/api/v2/ping")
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
