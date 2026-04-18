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
) {
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
