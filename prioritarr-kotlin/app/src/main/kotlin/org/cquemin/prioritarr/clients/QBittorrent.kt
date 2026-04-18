package org.cquemin.prioritarr.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonArray

/**
 * qBittorrent Web API v2 client. Mirrors prioritarr/clients/qbittorrent.py.
 * The qBit API tracks auth via cookie — Ktor HttpClient with the default
 * CIO engine maintains the cookie jar between calls.
 */
class QBitClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')
    @Volatile private var authenticated: Boolean = false

    suspend fun getTorrents(category: String? = null): JsonArray = request {
        http.get("$root/api/v2/torrents/info") {
            if (category != null) parameter("category", category)
        }.body()
    }

    suspend fun pause(hashes: List<String>) {
        request<Unit> {
            http.submitForm(
                url = "$root/api/v2/torrents/pause",
                formParameters = parameters { append("hashes", hashes.joinToString("|")) },
            )
            Unit
        }
    }

    suspend fun resume(hashes: List<String>) {
        request<Unit> {
            http.submitForm(
                url = "$root/api/v2/torrents/resume",
                formParameters = parameters { append("hashes", hashes.joinToString("|")) },
            )
            Unit
        }
    }

    suspend fun topPriority(hashes: List<String>) {
        request<Unit> {
            http.submitForm(
                url = "$root/api/v2/torrents/topPrio",
                formParameters = parameters { append("hashes", hashes.joinToString("|")) },
            )
            Unit
        }
    }

    suspend fun bottomPriority(hashes: List<String>) {
        request<Unit> {
            http.submitForm(
                url = "$root/api/v2/torrents/bottomPrio",
                formParameters = parameters { append("hashes", hashes.joinToString("|")) },
            )
            Unit
        }
    }

    private suspend fun login() {
        http.submitForm(
            url = "$root/api/v2/auth/login",
            formParameters = Parameters.build {
                append("username", username)
                append("password", password)
            },
        )
        authenticated = true
    }

    /** Retry the request once with a login if qBit returns 401/403 and we haven't authenticated. */
    private suspend fun <T> request(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ClientRequestException) {
            if ((e.response.status == HttpStatusCode.Unauthorized ||
                 e.response.status == HttpStatusCode.Forbidden) &&
                !authenticated) {
                login()
                block()
            } else throw e
        }
}
