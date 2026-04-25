package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Thin wrapper around the Tautulli v2 API. Mirrors prioritarr/clients/tautulli.py. */
class TautulliClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    suspend fun getShowLibraries(): JsonArray {
        val data = call("get_libraries") as JsonArray
        return JsonArray(data.filter {
            (it.jsonObject["section_type"]?.jsonPrimitiveOrNull?.contentOrNull == "show")
        })
    }

    suspend fun getHistory(
        grandparentRatingKey: String? = null,
        mediaType: String? = null,
        length: Int = 1000,
    ): JsonArray {
        val params = mutableMapOf<String, String>("length" to length.toString())
        grandparentRatingKey?.let { params["grandparent_rating_key"] = it }
        mediaType?.let { params["media_type"] = it }
        val data = call("get_history", params) as JsonObject
        return data["data"] as JsonArray
    }

    suspend fun getMetadata(ratingKey: String): JsonElement =
        call("get_metadata", mapOf("rating_key" to ratingKey))

    suspend fun getLibraryMediaInfo(sectionId: Int, length: Int = 5000): JsonArray {
        val data = call(
            "get_library_media_info",
            mapOf("section_id" to sectionId.toString(), "length" to length.toString()),
        ) as JsonObject
        return data["data"] as JsonArray
    }

    private suspend fun call(cmd: String, params: Map<String, String> = emptyMap()): JsonElement {
        val response: JsonObject = http.get("$root/api/v2") {
            parameter("apikey", apiKey)
            parameter("cmd", cmd)
            for ((k, v) in params) parameter(k, v)
        }.body()
        // Tautulli wraps everything in {"response":{"result":"success","data": ...}}
        return (response["response"] as JsonObject)["data"]
            ?: error("Tautulli cmd=$cmd returned no data field")
    }
}

private val JsonElement.jsonPrimitiveOrNull
    get() = this as? kotlinx.serialization.json.JsonPrimitive
private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content
