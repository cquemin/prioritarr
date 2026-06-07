package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yoshiz.app.prioritarr.backend.http.ClientJson

/**
 * Minimal Tdarr client. Tdarr exposes no dedicated REST endpoint for
 * pausing; its UI drives everything through a generic CRUD endpoint
 * (`/api/v2/cruddb`) against its embedded JSON DB. We use it to read and
 * flip the global `pauseAllNodes` flag, which stops/resumes every
 * transcode worker across all nodes.
 *
 * Hand-built JSON request bodies (rather than serializer round-trips) so
 * the wire format matches exactly what the Tdarr server expects.
 */
class TdarrClient(
    private val baseUrl: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    /** Current value of the global pauseAllNodes flag. false if unknown/unreachable-shaped. */
    suspend fun isPaused(): Boolean {
        val text = post("""{"data":{"collection":"SettingsGlobalJSONDB","mode":"getAll"}}""")
        if (text.isBlank()) return false
        val arr = ClientJson.parseToJsonElement(text) as? JsonArray ?: return false
        val global = arr.firstOrNull()?.jsonObject ?: return false
        return global["pauseAllNodes"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /** Set the global pauseAllNodes flag. true = stop all transcode workers. */
    suspend fun setPaused(paused: Boolean) {
        post(
            """{"data":{"collection":"SettingsGlobalJSONDB","mode":"update",""" +
                """"docID":"globalsettings","obj":{"pauseAllNodes":$paused}}}""",
        )
    }

    private suspend fun post(jsonBody: String): String =
        http.post("$root/api/v2/cruddb") {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }.bodyAsText()
}
