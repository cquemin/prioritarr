package org.yoshiz.app.prioritarr.backend.api.v2

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.app.AppState
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.events.AppEvent

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.api.v2.EventsRoute")
private val sseJson = Json { encodeDefaults = true; explicitNulls = false }

/**
 * GET /api/v2/events — Server-Sent Events stream.
 *
 * Ktor 2.3 doesn't ship an SSE plugin (added in 3.x), so the stream is
 * written manually via respondBytesWriter. The spec lines format is
 * `event: <type>\nid: <id>\ndata: <json>\n\n`.
 *
 * On connect:
 * 1. If `Last-Event-ID` header (or ?lastEventId= query param) is set,
 *    replay ringBuffer.since(id) before attaching to the live flow.
 * 2. Subscribe to eventBus.flow and write every new event.
 * 3. Heartbeat every 30s is emitted by [startHeartbeat] — a separate
 *    coroutine launched at app start.
 */
fun Route.eventsRoute(state: AppState) {
    get("/events") {
        val lastEventId = (call.request.header("Last-Event-ID")
            ?: call.request.queryParameters["lastEventId"])?.toLongOrNull()

        // Suppress reverse-proxy buffering — Nginx/Traefik may otherwise
        // hold bytes until a size threshold or timeout.
        call.response.header("X-Accel-Buffering", "no")
        call.response.header("Cache-Control", "no-cache, no-transform")

        call.respondBytesWriter(contentType = ContentType("text", "event-stream")) {
            // Replay any buffered events the client missed.
            val replay = state.eventBus.buffer.since(lastEventId)
            for (event in replay) writeEvent(event)

            // Track the highest id we've sent so we don't double-send an
            // event that's simultaneously in the replay window and the flow.
            var lastSentId = replay.lastOrNull()?.id ?: lastEventId ?: 0L

            val channel = this
            val job = SupervisorJob()
            state.eventBus.flow
                .onEach { event ->
                    if (event.id > lastSentId) {
                        channel.writeEvent(event)
                        lastSentId = event.id
                    }
                }
                .launchIn(kotlinx.coroutines.CoroutineScope(job + Dispatchers.Default))

            try {
                while (isActive) delay(1_000)
            } catch (_: CancellationException) {
                // Client disconnected — fall through to cleanup.
            } finally {
                job.cancel()
            }
        }
    }
}

private suspend fun io.ktor.utils.io.ByteWriteChannel.writeEvent(event: AppEvent) {
    val data = sseJson.encodeToString(event.payload)
    writeStringUtf8("event: ${event.type}\n")
    writeStringUtf8("id: ${event.id}\n")
    writeStringUtf8("data: $data\n\n")
    flush()
}

/**
 * Heartbeat publisher — emits one `heartbeat` event every 30s. Launched
 * once at app startup; every SSE subscriber sees these heartbeats.
 */
fun startHeartbeat(state: AppState, parentJob: Job): Job =
    kotlinx.coroutines.CoroutineScope(parentJob + Dispatchers.Default).launch {
        while (isActive) {
            try {
                state.eventBus.publish(
                    "heartbeat",
                    buildJsonObject { put("ts", Database.nowIsoOffset()) } as JsonObject,
                )
            } catch (e: Exception) {
                logger.warn("heartbeat event publish failed: ${e.message}")
            }
            delay(30_000)
        }
    }
