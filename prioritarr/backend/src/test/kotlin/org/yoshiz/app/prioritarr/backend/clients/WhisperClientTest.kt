package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperClientTest {

    private fun tempWav(): java.nio.file.Path {
        val p = java.nio.file.Files.createTempFile("whisper-test", ".wav")
        java.nio.file.Files.write(p, ByteArray(64))
        p.toFile().deleteOnExit()
        return p
    }

    @Test
    fun posts_translate_task_and_returns_srt() = runBlocking {
        var seenUrl = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(
                content = "1\n00:00:01,000 --> 00:00:02,000\nHello\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        val srt = client.translateToSrt(tempWav(), sourceLang = "ja")

        assertTrue(srt!!.startsWith("1\n"))
        assertTrue("task=translate" in seenUrl, "must request translation, not transcription: $seenUrl")
        assertTrue("language=ja" in seenUrl, seenUrl)
        assertTrue("output=srt" in seenUrl, seenUrl)
    }

    @Test
    fun omits_language_when_unknown_so_whisper_detects_it() = runBlocking {
        var seenUrl = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond("1\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        client.translateToSrt(tempWav(), sourceLang = null)

        assertTrue("language=" !in seenUrl, "must not send an empty language: $seenUrl")
    }

    @Test
    fun returns_null_on_upstream_error() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        assertNull(client.translateToSrt(tempWav(), sourceLang = "ja"))
    }

    @Test
    fun returns_null_on_empty_body() = runBlocking {
        val engine = MockEngine {
            respond("   ", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = WhisperClient(HttpClient(engine), "http://whisper:9000")

        assertNull(client.translateToSrt(tempWav(), sourceLang = "ja"))
    }
}
