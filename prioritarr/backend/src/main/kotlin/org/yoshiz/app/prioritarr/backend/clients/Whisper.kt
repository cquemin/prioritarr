package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Thin client for openai-whisper-asr-webservice (`onerahmet/...`).
 *
 * We use `task=translate`, which takes non-English audio straight to
 * English text in one pass. The two-step alternative (transcribe to
 * Japanese, then translate) doubles CPU and compounds transcription
 * errors through the translator, and the translator in this stack
 * (Lingarr) has never run.
 *
 * This exists because Bazarr cannot be made to do it: its per-episode
 * endpoint calls `generate_subtitles(..., fallback_allowed=False)` with
 * the default and never passes the flag, so no Bazarr configuration can
 * make a per-episode search reach Whisper.
 */
class WhisperClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(WhisperClient::class.java)
    private val root = baseUrl.trimEnd('/')

    /**
     * Translate [wav] to an English SRT document.
     *
     * @param sourceLang ISO-639-1 code of the audio (e.g. "ja"), or null
     *   to let Whisper detect it. Never send an empty value — the
     *   service treats a blank `language` as invalid rather than absent.
     * @return the SRT body, or null on any failure (caller maps that to
     *   UPSTREAM_DOWN, which does not consume a backoff attempt).
     */
    suspend fun translateToSrt(wav: Path, sourceLang: String?): String? = try {
        val langParam = sourceLang?.takeIf { it.isNotBlank() }?.let { "&language=$it" } ?: ""
        val url = "$root/asr?task=translate&output=srt$langParam"
        val bytes = Files.readAllBytes(wav)
        val resp: HttpResponse = http.post(url) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "audio_file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                            },
                        )
                    },
                ),
            )
        }
        if (resp.status.value !in 200..299) {
            logger.warn("whisper: HTTP {} for {}", resp.status.value, wav.fileName)
            null
        } else {
            resp.bodyAsText().takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        logger.warn("whisper: request failed for {}: {}", wav.fileName, e.message)
        null
    }
}
