package org.cquemin.prioritarr.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

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
}
