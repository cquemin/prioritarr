package org.yoshiz.app.prioritarr.backend.webhooks

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Idempotent registration of prioritarr's inbound webhooks against
 * Sonarr / Tautulli. Both upstreams expose APIs to create or update
 * notifications; we use those so the operator doesn't have to copy/
 * paste URLs into the upstream UIs.
 *
 * SAB is the odd one out — its post-processing scripts are file-based
 * (a script file in a path on the SAB container, not a URL config).
 * No API surface to inject one without a shared filesystem, so we
 * leave SAB's setup manual and surface the script content through a
 * separate endpoint.
 *
 * Webhook URL strategy: register the *internal* Docker network address
 * (`http://prioritarr:8000/...`) — bypasses Traefik+Authelia, which
 * would otherwise reject the upstream's request for missing browser
 * cookies. The X-Api-Key header is added so prioritarr's auth still
 * passes.
 */

/** Name we use for our webhook in upstream services (idempotency key). */
const val PRIORITARR_NOTIFIER_NAME = "Prioritarr"

/** Internal Docker DNS name for prioritarr — Sonarr/Tautulli reach this. */
const val PRIORITARR_INTERNAL_BASE = "http://prioritarr:8000"

@Serializable
data class WebhookStatusResult(
    val service: String,
    val configured: Boolean,
    /** True when this service can't be auto-configured (SAB). */
    val manual: Boolean = false,
    val url: String? = null,
    val name: String? = null,
    val detail: String? = null,
)

/* ----------------------------- Sonarr ----------------------------- */

private fun sonarrWebhookPayload(prioritarrApiKey: String?): JsonObject = buildJsonObject {
    put("name", PRIORITARR_NOTIFIER_NAME)
    put("implementation", "Webhook")
    put("configContract", "WebhookSettings")
    // Trigger flags Sonarr understands; mirror Module.kt's eventType
    // routing — Grab/Download/EpisodeFileDelete/SeriesDelete/ManualInteractionRequired.
    put("onGrab", true)
    put("onDownload", true)
    put("onUpgrade", true)
    put("onRename", false)
    put("onSeriesDelete", true)
    put("onEpisodeFileDelete", true)
    put("onEpisodeFileDeleteForUpgrade", true)
    put("onHealthIssue", false)
    put("onApplicationUpdate", false)
    put("onManualInteractionRequired", true)
    put("includeHealthWarnings", false)
    put("supportsOnGrab", true)
    put("supportsOnDownload", true)
    put("supportsOnUpgrade", true)
    put("supportsOnRename", true)
    put("supportsOnSeriesDelete", true)
    put("supportsOnEpisodeFileDelete", true)
    put("supportsOnEpisodeFileDeleteForUpgrade", true)
    put("supportsOnHealthIssue", true)
    put("supportsOnApplicationUpdate", true)
    put("supportsOnManualInteractionRequired", true)
    put("tags", buildJsonArray { })
    put("fields", buildJsonArray {
        addJsonObject {
            put("name", "url")
            put("value", "$PRIORITARR_INTERNAL_BASE/api/sonarr/on-grab")
        }
        addJsonObject {
            put("name", "method")
            put("value", 1)  // POST
        }
        // X-Api-Key custom header so prioritarr's /api/v2 auth doesn't
        // 401 the inbound webhook. Sonarr v4 supports custom headers
        // via the headers field; format is comma-separated key:value.
        addJsonObject {
            put("name", "headers")
            put("value", buildJsonArray {
                addJsonObject {
                    put("name", "X-Api-Key")
                    put("value", prioritarrApiKey.orEmpty())
                }
            })
        }
    })
}

suspend fun configureSonarrWebhook(
    sonarrUrl: String,
    sonarrApiKey: String,
    prioritarrApiKey: String?,
    http: HttpClient,
): WebhookStatusResult {
    val root = sonarrUrl.trimEnd('/')
    val existing = listSonarrNotifications(root, sonarrApiKey, http)
    val ours = existing.firstOrNull {
        (it.jsonObject["name"]?.jsonPrimitive?.contentOrNull) == PRIORITARR_NOTIFIER_NAME
    }
    val payload = sonarrWebhookPayload(prioritarrApiKey)
    return try {
        if (ours != null) {
            val id = ours.jsonObject["id"]?.jsonPrimitive?.longOrNull
                ?: return WebhookStatusResult("sonarr", false, detail = "existing notifier missing id")
            val merged = buildJsonObject {
                for ((k, v) in payload) put(k, v)
                put("id", id)
            }
            http.put("$root/api/v3/notification/$id") {
                header("X-Api-Key", sonarrApiKey)
                contentType(ContentType.Application.Json)
                setBody(merged)
            }.body<JsonObject>()
        } else {
            http.post("$root/api/v3/notification") {
                header("X-Api-Key", sonarrApiKey)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }.body<JsonObject>()
        }
        WebhookStatusResult(
            service = "sonarr",
            configured = true,
            url = "$PRIORITARR_INTERNAL_BASE/api/sonarr/on-grab",
            name = PRIORITARR_NOTIFIER_NAME,
        )
    } catch (e: Throwable) {
        WebhookStatusResult("sonarr", false, detail = e.message ?: "configure failed")
    }
}

suspend fun getSonarrWebhookStatus(
    sonarrUrl: String,
    sonarrApiKey: String,
    http: HttpClient,
): WebhookStatusResult {
    val root = sonarrUrl.trimEnd('/')
    return try {
        val list = listSonarrNotifications(root, sonarrApiKey, http)
        val ours = list.firstOrNull {
            (it.jsonObject["name"]?.jsonPrimitive?.contentOrNull) == PRIORITARR_NOTIFIER_NAME
        }
        if (ours == null) {
            WebhookStatusResult("sonarr", configured = false)
        } else {
            val urlField = (ours.jsonObject["fields"] as? JsonArray)
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "url" }
                ?.get("value")?.jsonPrimitive?.contentOrNull
            WebhookStatusResult(
                service = "sonarr",
                configured = true,
                url = urlField,
                name = PRIORITARR_NOTIFIER_NAME,
            )
        }
    } catch (e: Throwable) {
        WebhookStatusResult("sonarr", false, detail = e.message ?: "status check failed")
    }
}

private suspend fun listSonarrNotifications(
    root: String, apiKey: String, http: HttpClient,
): JsonArray = http.get("$root/api/v3/notification") {
    header("X-Api-Key", apiKey)
}.body()

/* ---------------------------- Tautulli ---------------------------- */

/** Tautulli's notifier `agent_id` for Webhook (8 in current Tautulli). */
private const val TAUTULLI_WEBHOOK_AGENT_ID = 8

suspend fun configureTautulliWebhook(
    tautulliUrl: String,
    tautulliApiKey: String,
    prioritarrApiKey: String?,
    http: HttpClient,
): WebhookStatusResult {
    val root = tautulliUrl.trimEnd('/')
    return try {
        // 1. Find or create our webhook notifier.
        val existingId = findTautulliNotifierId(root, tautulliApiKey, http)
        val notifierId = existingId ?: run {
            // add_notifier_config returns the new notifier_id in
            // response.data — Tautulli wraps everything in
            // {response: {result, data, message}}.
            val resp = http.get("$root/api/v2") {
                parameter("apikey", tautulliApiKey)
                parameter("cmd", "add_notifier_config")
                parameter("agent_id", TAUTULLI_WEBHOOK_AGENT_ID.toString())
            }.body<JsonObject>()
            val data = resp["response"]?.jsonObject?.get("data")?.jsonObject
            data?.get("notifier_id")?.jsonPrimitive?.longOrNull
                ?: return WebhookStatusResult("tautulli", false, detail = "add_notifier_config returned no id")
        }

        // 2. Configure our notifier with URL, headers, watched-event trigger.
        val webhookUrl = "$PRIORITARR_INTERNAL_BASE/api/plex-event"
        http.get("$root/api/v2") {
            parameter("apikey", tautulliApiKey)
            parameter("cmd", "set_notifier_config")
            parameter("notifier_id", notifierId.toString())
            parameter("agent_id", TAUTULLI_WEBHOOK_AGENT_ID.toString())
            parameter("friendly_name", PRIORITARR_NOTIFIER_NAME)
            parameter("webhook_hook", webhookUrl)
            parameter("webhook_method", "POST")
            // X-Api-Key header — Tautulli's webhook agent supports it.
            parameter("webhook_headers", "X-Api-Key: ${prioritarrApiKey.orEmpty()}")
            // Body template: minimum viable — Tautulli substitutes
            // {plex_id} → the rating-key the user just watched.
            parameter("on_watched", "1")
            parameter(
                "on_watched_body",
                """{"event":"watched","grandparent_rating_key":"{grandparent_rating_key}","media_type":"{media_type}","rating_key":"{rating_key}"}""",
            )
        }.body<JsonObject>()

        WebhookStatusResult(
            service = "tautulli",
            configured = true,
            url = webhookUrl,
            name = PRIORITARR_NOTIFIER_NAME,
        )
    } catch (e: Throwable) {
        WebhookStatusResult("tautulli", false, detail = e.message ?: "configure failed")
    }
}

suspend fun getTautulliWebhookStatus(
    tautulliUrl: String,
    tautulliApiKey: String,
    http: HttpClient,
): WebhookStatusResult {
    val root = tautulliUrl.trimEnd('/')
    return try {
        val id = findTautulliNotifierId(root, tautulliApiKey, http)
        if (id == null) WebhookStatusResult("tautulli", configured = false)
        else {
            // Fetch the notifier config to surface the URL.
            val resp = http.get("$root/api/v2") {
                parameter("apikey", tautulliApiKey)
                parameter("cmd", "get_notifier_config")
                parameter("notifier_id", id.toString())
            }.body<JsonObject>()
            val cfg = resp["response"]?.jsonObject?.get("data")?.jsonObject
            val url = cfg?.get("config")?.jsonObject?.get("hook")?.jsonPrimitive?.contentOrNull
            WebhookStatusResult(
                service = "tautulli",
                configured = true,
                url = url,
                name = PRIORITARR_NOTIFIER_NAME,
            )
        }
    } catch (e: Throwable) {
        WebhookStatusResult("tautulli", false, detail = e.message ?: "status check failed")
    }
}

private suspend fun findTautulliNotifierId(
    root: String, apiKey: String, http: HttpClient,
): Long? {
    val list = http.get("$root/api/v2") {
        parameter("apikey", apiKey)
        parameter("cmd", "get_notifiers")
    }.body<JsonObject>()
    val data = list["response"]?.jsonObject?.get("data") as? JsonArray ?: return null
    return data.map { it.jsonObject }.firstOrNull {
        it["friendly_name"]?.jsonPrimitive?.contentOrNull == PRIORITARR_NOTIFIER_NAME ||
            it["agent_label"]?.jsonPrimitive?.contentOrNull == "Webhook"
    }?.get("id")?.jsonPrimitive?.longOrNull
}

/* ----------------------------- SAB ------------------------------- */

/**
 * SABnzbd doesn't support URL-config for post-processing scripts —
 * scripts are file-based, dropped into SAB's `script_dir`. We can't
 * inject one without a shared filesystem. The status endpoint always
 * returns `manual = true` so the UI shows the right affordance.
 */
fun getSabWebhookStatus(): WebhookStatusResult = WebhookStatusResult(
    service = "sab",
    configured = false,
    manual = true,
    detail = "SAB scripts are file-based; place sab-notify.sh in your SAB scripts directory.",
)
