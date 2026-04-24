package org.yoshiz.app.prioritarr.backend.clients

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
) : DownloadClient {
    override val clientName: String = "qbit"

    override suspend fun pauseOne(clientId: String) = pause(listOf(clientId))
    override suspend fun resumeOne(clientId: String) = resume(listOf(clientId))
    override suspend fun boostOne(clientId: String) = topPriority(listOf(clientId))
    override suspend fun demoteOne(clientId: String) = bottomPriority(listOf(clientId))
    override suspend fun deleteOne(clientId: String, deleteFiles: Boolean) =
        delete(listOf(clientId), deleteFiles = deleteFiles)

    /**
     * For qBit, the prioritarr label translates to a pause/resume
     * decision: P1 boosted + unpaused, P4/P5 paused (when higher
     * bands are actively downloading the caller is expected to fire
     * this per-download; the global pause-band calculation lives in
     * [org.yoshiz.app.prioritarr.backend.enforcement.computeQBitPauseActions]).
     */
    override suspend fun applyPriority(clientId: String, prioritarrPriority: Int) {
        when (prioritarrPriority) {
            1 -> topPriority(listOf(clientId))
            2, 3 -> resume(listOf(clientId))
            4, 5 -> {
                // Caller (usually the reconciler) decides whether to actually
                // pause based on the active-queue band rule. The interface
                // contract is "translate P to client model" — for qBit at
                // P4/P5 the translation is "demote to bottom"; the reconciler
                // fires a pause separately when the band calls for it.
                bottomPriority(listOf(clientId))
            }
        }
    }

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

    /**
     * Permanently delete torrents from the client. [deleteFiles] also
     * removes the partially-downloaded data on disk — used by the
     * janitor to free up storage when blacklisting stuck releases.
     */
    suspend fun delete(hashes: List<String>, deleteFiles: Boolean = true) {
        if (hashes.isEmpty()) return
        request<Unit> {
            http.submitForm(
                url = "$root/api/v2/torrents/delete",
                formParameters = parameters {
                    append("hashes", hashes.joinToString("|"))
                    append("deleteFiles", deleteFiles.toString())
                },
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
