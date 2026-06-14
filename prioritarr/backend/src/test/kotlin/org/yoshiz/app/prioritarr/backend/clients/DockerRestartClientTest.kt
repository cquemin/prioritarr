package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DockerRestartClientTest {
    @Test fun restartContainer_posts_to_docker_restart_endpoint() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            method = req.method
            respond("", HttpStatusCode.NoContent)
        }
        val client = DockerRestartClient("http://dockerproxy:2375", HttpClient(engine))

        client.restartContainer("sonarr")

        assertEquals(HttpMethod.Post, method)
        assertEquals("/containers/sonarr/restart", path)
    }
}
