package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** SABnzbd JSON API client. Mirrors prioritarr/clients/sabnzbd.py. */
class SABClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient,
) : DownloadClient {
    override val clientName: String = "sab"

    override suspend fun pauseOne(clientId: String) {
        setPriority(clientId, PRIORITY_MAP[5] ?: -1)
    }
    override suspend fun resumeOne(clientId: String) {
        // SAB has no strict "resume"; normal priority is the default.
        setPriority(clientId, PRIORITY_MAP[3] ?: 0)
    }
    override suspend fun boostOne(clientId: String) {
        setPriority(clientId, 2)  // Force — bypasses queue-pauses
    }
    override suspend fun demoteOne(clientId: String) {
        setPriority(clientId, -1)  // Low
    }
    override suspend fun applyPriority(clientId: String, prioritarrPriority: Int) {
        setPriority(clientId, PRIORITY_MAP[prioritarrPriority] ?: 0)
    }

    private val root: String = baseUrl.trimEnd('/')

    suspend fun getQueue(): JsonArray {
        val data = call("queue") as JsonObject
        return (data["queue"] as JsonObject)["slots"] as JsonArray
    }

    suspend fun setPriority(nzoId: String, priority: Int): JsonElement =
        call("queue", mapOf(
            "name" to "priority",
            "value" to nzoId,
            "value2" to priority.toString(),
        ))

    suspend fun moveToPosition(nzoId: String, position: Int): JsonElement =
        call("switch", mapOf("value" to nzoId, "value2" to position.toString()))

    /**
     * Most recent N history items. Used by the queue janitor to find
     * Failed entries (network errors, missing articles, par failures,
     * duplicate-detected, etc.) that need to be removed + re-searched.
     *
     * Each slot has at minimum: nzo_id, status, fail_message, name.
     */
    suspend fun getHistory(limit: Int = 100): JsonArray {
        val data = call("history", mapOf("limit" to limit.toString())) as JsonObject
        return (data["history"] as JsonObject)["slots"] as JsonArray
    }

    /**
     * Delete a queue or history entry. SAB uses the same endpoint with
     * different `value` semantics depending on which list the nzo_id
     * lives in; we expose both modes.
     *
     * `delFiles=1` purges any partial download from disk.
     */
    suspend fun deleteFromQueue(nzoId: String, delFiles: Boolean = true): JsonElement =
        call("queue", mapOf(
            "name" to "delete",
            "value" to nzoId,
            "del_files" to if (delFiles) "1" else "0",
        ))

    suspend fun deleteFromHistory(nzoId: String, delFiles: Boolean = true): JsonElement =
        call("history", mapOf(
            "name" to "delete",
            "value" to nzoId,
            "del_files" to if (delFiles) "1" else "0",
        ))

    private suspend fun call(mode: String, params: Map<String, String> = emptyMap()): JsonElement =
        http.get("$root/sabnzbd/api") {
            parameter("apikey", apiKey)
            parameter("mode", mode)
            parameter("output", "json")
            for ((k, v) in params) parameter(k, v)
        }.body()

    companion object {
        /**
         * Maps internal priority levels (P1–P5) to SABnzbd priority values:
         * P1 → Force (2), P2 → High (1), P3 → Normal (0), P4/P5 → Low (-1).
         * Mirrors PRIORITY_MAP in prioritarr/clients/sabnzbd.py.
         */
        val PRIORITY_MAP: Map<Int, Int> = mapOf(1 to 2, 2 to 1, 3 to 0, 4 to -1, 5 to -1)
    }
}
