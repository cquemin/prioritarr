package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrCancelCommandTest {
    @Test fun cancelCommand_deletes_command_endpoint() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        val engine = MockEngine { req ->
            path = req.url.encodedPath; method = req.method
            respond("{}", HttpStatusCode.OK)
        }
        val sonarr = SonarrClient("http://sonarr:8989", "k", HttpClient(engine))

        sonarr.cancelCommand(42L)

        assertEquals(HttpMethod.Delete, method)
        assertEquals("/api/v3/command/42", path)
    }
}
