package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Thin wrapper around the Sonarr v3 REST API. Mirrors prioritarr/clients/sonarr.py. */
class SonarrClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    suspend fun getAllSeries(): JsonArray =
        get("/api/v3/series").jsonArray

    suspend fun getSeries(seriesId: Long): JsonObject =
        get("/api/v3/series/$seriesId") as JsonObject

    suspend fun getEpisodes(seriesId: Long): JsonArray =
        get("/api/v3/episode", mapOf("seriesId" to seriesId.toString())).jsonArray

    suspend fun getWantedMissing(pageSize: Int = 1000): JsonArray =
        ((get("/api/v3/wanted/missing", mapOf("pageSize" to pageSize.toString())) as JsonObject)
            ["records"] as JsonArray)

    suspend fun getWantedCutoff(pageSize: Int = 1000): JsonArray =
        ((get("/api/v3/wanted/cutoff", mapOf("pageSize" to pageSize.toString())) as JsonObject)
            ["records"] as JsonArray)

    suspend fun getQueue(pageSize: Int = 1000): JsonArray =
        ((get("/api/v3/queue", mapOf("pageSize" to pageSize.toString())) as JsonObject)
            ["records"] as JsonArray)

    suspend fun triggerSeriesSearch(seriesId: Long): JsonObject =
        post("/api/v3/command", buildJsonObject {
            put("name", "SeriesSearch")
            put("seriesId", seriesId)
        }) as JsonObject

    suspend fun triggerCutoffSearch(seriesId: Long): JsonObject =
        post("/api/v3/command", buildJsonObject {
            put("name", "CutoffUnmetEpisodeSearch")
            put("seriesId", seriesId)
        }) as JsonObject

    /**
     * Re-search a specific list of episodes. Used by the queue janitor
     * after deleting a stuck release: blacklist the bad release, then
     * tell Sonarr to find a new one for the same episode(s).
     */
    suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject =
        post("/api/v3/command", buildJsonObject {
            put("name", "EpisodeSearch")
            putJsonArray("episodeIds") { episodeIds.forEach { add(it) } }
        }) as JsonObject

    /**
     * Find the queue entry whose downloadId matches a known qBit hash
     * or SAB nzo_id, so the janitor can pull the linked
     * episodeIds + the release-info needed to blocklist properly.
     * Returns null if Sonarr has no queue entry for that download —
     * it may have been imported, removed, or never reached Sonarr.
     */
    suspend fun findQueueEntryForDownloadId(downloadId: String): JsonObject? {
        val q = getQueue()
        return q.firstOrNull {
            (it as? JsonObject)?.get("downloadId")
                ?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?.equals(downloadId, ignoreCase = true) == true
        } as? JsonObject
    }

    /**
     * Remove a queue entry from Sonarr's view AND blocklist the
     * release in one call. Sonarr will refuse to grab the same
     * release again until manually un-blocklisted.
     */
    suspend fun removeQueueEntry(
        queueId: Long,
        removeFromClient: Boolean = false,
        blocklist: Boolean = true,
    ): JsonElement =
        delete("/api/v3/queue/$queueId", mapOf(
            "removeFromClient" to removeFromClient.toString(),
            "blocklist" to blocklist.toString(),
            "skipRedownload" to "true",
        ))

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement =
        http.get("$root$path") {
            header("X-Api-Key", apiKey)
            for ((k, v) in params) parameter(k, v)
        }.body()

    private suspend fun post(path: String, body: JsonElement): JsonElement =
        http.post("$root$path") {
            header("X-Api-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    private suspend fun delete(path: String, params: Map<String, String> = emptyMap()): JsonElement =
        http.delete("$root$path") {
            header("X-Api-Key", apiKey)
            for ((k, v) in params) parameter(k, v)
        }.body()
}
