package org.yoshiz.app.prioritarr.backend.connections

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.ktor.client.request.forms.submitForm
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yoshiz.app.prioritarr.backend.ConnectionTestStatus
import org.yoshiz.app.prioritarr.backend.TraktApi

/**
 * Connection test result. Shape is shared with the frontend wire
 * type so the UI can render the right message + colour without
 * special-casing per service.
 *
 * `status` semantics:
 *   - ConnectionTestStatus.CONNECTED.wire         → upstream reachable AND auth accepted AND
 *                            response parses as expected
 *   - ConnectionTestStatus.CONNECTION_FAILED.wire → can't reach the host (DNS, refused, timeout)
 *   - ConnectionTestStatus.AUTH_FAILED.wire       → reached the host but credentials rejected
 *   - ConnectionTestStatus.VERSION_FAILED.wire    → reached + auth ok BUT response doesn't
 *                            match the schema we expect (wrong path
 *                            or unsupported upstream version)
 */
@Serializable
data class ConnectionTestResult(
    val ok: Boolean,
    val status: String,
    val detail: String? = null,
    /** Discovered version string when the upstream reports one. */
    val version: String? = null,
)

private val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Short-lived HTTP client used only for connection tests. 5s timeouts. */
private fun testClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(testJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 5_000
    }
    expectSuccess = false
}

private fun normalize(url: String): String = url.trimEnd('/')

/** Convert any exception into a ConnectionTestStatus.CONNECTION_FAILED.wire result with a short reason. */
private fun connectionFailure(e: Throwable): ConnectionTestResult {
    val msg = (e.message ?: e::class.simpleName ?: "unknown error")
        // Strip Ktor-specific prefixes that don't help the user.
        .replace("Connect timeout has expired ", "Timeout: ")
        .take(200)
    return ConnectionTestResult(ok = false, status = ConnectionTestStatus.CONNECTION_FAILED.wire, detail = msg)
}

/**
 * Sonarr: GET /api/v3/system/status with X-Api-Key. The endpoint is
 * minimal (single object) and includes a `version` field, which both
 * confirms auth and flags an unparseable upstream.
 */
suspend fun testSonarr(rawUrl: String, apiKey: String): ConnectionTestResult = testClient().use { http ->
    val url = "${normalize(rawUrl)}/api/v3/system/status"
    val resp: HttpResponse = try {
        http.get(url) { header("X-Api-Key", apiKey) }
    } catch (e: Throwable) { return@use connectionFailure(e) }
    when (resp.status.value) {
        in 200..299 -> {
            val body = try { resp.body<JsonObject>() }
            catch (_: Throwable) {
                return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire,
                    "Reached upstream but the response wasn't JSON — wrong URL path or unsupported Sonarr version.")
            }
            val version = body["version"]?.jsonPrimitive?.contentOrNull
            if (version == null) {
                ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Response missing `version` — likely wrong upstream.")
            } else {
                ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire, version = version)
            }
        }
        401, 403 -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "API key rejected (HTTP ${resp.status.value}).")
        404 -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "404 — wrong URL path or Sonarr v2 (we need v3).")
        else -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Unexpected HTTP ${resp.status.value}.")
    }
}

/**
 * Tautulli: GET ?cmd=arnold&apikey=… returns a quote when auth is OK
 * (yes really — that's their no-op health check). Returns
 * `{"response":{"result":"success", ...}}` when keyed correctly,
 * `{"response":{"result":"error", "message":"Invalid apikey"}}` when not.
 */
suspend fun testTautulli(rawUrl: String, apiKey: String): ConnectionTestResult = testClient().use { http ->
    val url = "${normalize(rawUrl)}/api/v2"
    val resp: HttpResponse = try {
        http.get(url) {
            parameter("cmd", "arnold")
            parameter("apikey", apiKey)
        }
    } catch (e: Throwable) { return@use connectionFailure(e) }
    if (resp.status.value !in 200..299) {
        return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "HTTP ${resp.status.value}.")
    }
    val body = try { resp.body<JsonObject>() }
    catch (_: Throwable) {
        return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire,
            "Reached upstream but the response wasn't JSON — wrong URL path or unsupported Tautulli version.")
    }
    val response = body["response"]?.jsonObject
        ?: return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Missing `response` object.")
    val result = response["result"]?.jsonPrimitive?.contentOrNull
    when (result) {
        "success" -> ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire)
        "error" -> {
            val msg = response["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, msg.ifBlank { "API key rejected by Tautulli." })
        }
        else -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Unknown result: $result")
    }
}

/**
 * qBittorrent: POST /api/v2/auth/login with username + password. The
 * response body is the literal text "Ok." on success, "Fails." on
 * bad credentials. Skip-auth-for-localhost setups still go through
 * this endpoint (it just always returns Ok.).
 */
suspend fun testQbit(rawUrl: String, username: String, password: String): ConnectionTestResult = testClient().use { http ->
    val url = "${normalize(rawUrl)}/api/v2/auth/login"
    val resp: HttpResponse = try {
        http.submitForm(
            url = url,
            formParameters = parameters {
                append("username", username)
                append("password", password)
            },
        )
    } catch (e: Throwable) { return@use connectionFailure(e) }
    val body = try { resp.bodyAsText().trim() } catch (_: Throwable) { "" }
    when {
        resp.status.value == 403 -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "qBit returned 403 — IP banned, or WebUI auth disabled but no host whitelisted.")
        body == "Ok." -> {
            // Probe the version too so the UI can show something useful.
            val v = try {
                http.get("${normalize(rawUrl)}/api/v2/app/version") {
                    header("Cookie", resp.headers["set-cookie"] ?: "")
                }.bodyAsText().trim()
            } catch (_: Throwable) { null }
            ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire, version = v)
        }
        body == "Fails." -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "Username or password rejected.")
        resp.status.value !in 200..299 -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "HTTP ${resp.status.value}.")
        else -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Unexpected response: ${body.take(80)}")
    }
}

/** SAB: GET ?mode=version&output=json&apikey=…. Returns {"version":"…"} on success. */
suspend fun testSab(rawUrl: String, apiKey: String): ConnectionTestResult = testClient().use { http ->
    val url = "${normalize(rawUrl)}/api"
    val resp: HttpResponse = try {
        http.get(url) {
            parameter("mode", "version")
            parameter("output", "json")
            parameter("apikey", apiKey)
        }
    } catch (e: Throwable) { return@use connectionFailure(e) }
    if (resp.status.value !in 200..299) {
        return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "HTTP ${resp.status.value}.")
    }
    val raw = try { resp.bodyAsText() } catch (_: Throwable) { "" }
    // SAB returns plain "API key incorrect" (not JSON) when the key is wrong.
    if (raw.contains("API key", ignoreCase = true) && raw.contains("incorrect", ignoreCase = true)) {
        return@use ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "API key rejected by SABnzbd.")
    }
    val body = try { Json.parseToJsonElement(raw) as JsonObject }
    catch (_: Throwable) {
        return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Reached upstream but the response wasn't JSON — wrong URL path or auth disabled.")
    }
    val version = body["version"]?.jsonPrimitive?.contentOrNull
    if (version != null) ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire, version = version)
    else ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Missing `version` field — wrong upstream.")
}

/** Plex: GET /identity with X-Plex-Token. XML response with MediaContainer root. */
suspend fun testPlex(rawUrl: String, token: String): ConnectionTestResult = testClient().use { http ->
    val url = "${normalize(rawUrl)}/identity"
    val resp: HttpResponse = try {
        http.get(url) { header("X-Plex-Token", token) }
    } catch (e: Throwable) { return@use connectionFailure(e) }
    when (resp.status.value) {
        in 200..299 -> {
            val raw = try { resp.bodyAsText() } catch (_: Throwable) { "" }
            // Plex returns either XML or JSON depending on Accept header.
            // We don't set Accept, so default is XML <MediaContainer .../>.
            // Either format counts; we just check for the marker word.
            if (raw.contains("MediaContainer", ignoreCase = true)) {
                // Try to extract version from the XML attribute. Cheap regex
                // — full XML parser is overkill for an attribute scrape.
                val v = Regex("""version="([^"]+)"""").find(raw)?.groupValues?.get(1)
                ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire, version = v)
            } else {
                ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Reached upstream but body didn't look like Plex.")
            }
        }
        401 -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "Plex token rejected (HTTP 401).")
        else -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "HTTP ${resp.status.value}.")
    }
}

/** Trakt: GET /users/me with the configured client_id + access token. */
suspend fun testTrakt(clientId: String, accessToken: String): ConnectionTestResult = testClient().use { http ->
    val url = "${TraktApi.BASE_URL}/users/me"
    val resp: HttpResponse = try {
        http.get(url) {
            header("trakt-api-version", "2")
            header("trakt-api-key", clientId)
            header("Authorization", "Bearer $accessToken")
        }
    } catch (e: Throwable) { return@use connectionFailure(e) }
    when (resp.status.value) {
        in 200..299 -> {
            val body = try { resp.body<JsonObject>() }
            catch (_: Throwable) {
                return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Trakt response wasn't JSON.")
            }
            val username = body["username"]?.jsonPrimitive?.contentOrNull
            if (username != null) ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire, detail = "Logged in as @$username")
            else ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Trakt response missing `username`.")
        }
        401 -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "Access token rejected — refresh or reconnect.")
        403 -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "Forbidden — client_id mismatch with token.")
        else -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "HTTP ${resp.status.value}.")
    }
}
