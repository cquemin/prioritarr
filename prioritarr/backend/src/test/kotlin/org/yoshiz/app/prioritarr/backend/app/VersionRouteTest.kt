package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionRouteTest {
    @Test fun returns_200_with_version_fields() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { versionRoute() }
        }
        val resp = client.get("/version")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue("\"version\"" in body, "missing version: $body")
        assertTrue("\"gitSha\"" in body, "missing gitSha: $body")
        assertTrue("\"buildTime\"" in body, "missing buildTime: $body")
    }
}
