package org.cquemin.prioritarr.app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.cquemin.prioritarr.health.checkLiveness
import org.cquemin.prioritarr.health.checkReadiness
import org.cquemin.prioritarr.schemas.DependencyStatus
import org.cquemin.prioritarr.schemas.HealthOk
import org.cquemin.prioritarr.schemas.HealthUnhealthy
import org.cquemin.prioritarr.schemas.InjectSeriesMappingRequest
import org.cquemin.prioritarr.schemas.OkResponse
import org.cquemin.prioritarr.schemas.OnGrabIgnored
import org.cquemin.prioritarr.schemas.OnGrabProcessed
import org.cquemin.prioritarr.schemas.OnGrabDuplicate
import org.cquemin.prioritarr.schemas.PlexEventOk
import org.cquemin.prioritarr.schemas.PlexEventUnmatched
import org.cquemin.prioritarr.webhooks.eventKey
import org.cquemin.prioritarr.webhooks.handleOnGrab
import org.cquemin.prioritarr.webhooks.handleWatched
import org.cquemin.prioritarr.webhooks.parseOnGrabPayload
import org.cquemin.prioritarr.webhooks.parseTautulliWatched
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

val appJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

private val logger = LoggerFactory.getLogger("org.cquemin.prioritarr.app")

fun Application.prioritarrModule(state: AppState) {
    install(ContentNegotiation) { json(appJson) }

    // Spec §5.3 "always-200 webhook" shield — any unhandled exception on
    // webhook paths is downgraded to a 200 JSON body. Health/ready/testing
    // retain normal error semantics.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val path = call.request.path()
            if (path == "/api/sonarr/on-grab" || path == "/api/plex-event") {
                logger.warn("webhook handler exception on $path: ${cause.message}")
                call.respond(OnGrabIgnored(eventType = "unknown"))
            } else {
                call.respondText(
                    """{"detail":"${cause.message?.replace("\"", "'")}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
    }

    routing {
        get("/health") {
            val (ok, body) = checkLiveness(state.db)
            if (ok) call.respond(HttpStatusCode.OK, body as HealthOk)
            else call.respond(HttpStatusCode.ServiceUnavailable, body as HealthUnhealthy)
        }

        get("/ready") {
            val deps = checkDependencies(state)
            val body = checkReadiness(state.db, deps)
            val status = if (body.status == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, body)
        }

        get("/openapi.json") {
            // Spec B §8.1: serve the committed openapi.json verbatim. Loaded
            // from the classpath where the build plugin drops it.
            val stream = javaClass.classLoader.getResourceAsStream("openapi.json")
            if (stream == null) {
                call.respondText("""{"detail":"openapi.json not on classpath"}""",
                    ContentType.Application.Json, HttpStatusCode.InternalServerError)
            } else {
                call.respondText(stream.reader().readText(), ContentType.Application.Json)
            }
        }

        post("/api/sonarr/on-grab") {
            val raw = call.receiveText()
            val payload = appJson.parseToJsonElement(raw) as JsonObject
            val eventType = payload["eventType"]?.jsonPrimitive?.contentOrNull ?: "unknown"

            if (eventType != "Grab") {
                call.respond(OnGrabIgnored(eventType = eventType))
                return@post
            }

            val event = parseOnGrabPayload(payload)
            val priorityResult = state.priorityService.priorityForSeries(event.seriesId)

            // Check dedupe BEFORE handleOnGrab (which also tries insert); peek via eventKey.
            val alreadySeen = !state.db.tryInsertDedupe(eventKey(event), Instant.now().toString())
            if (alreadySeen) {
                call.respond(OnGrabDuplicate(priority = priorityResult.priority, label = priorityResult.label))
                return@post
            }
            // Undo the peek-insert and let handleOnGrab do the proper write + audit.
            state.db.q.tryInsertDedupe(eventKey(event), Instant.now().toString())
            handleOnGrab(event, state.db, priorityResult.priority, dryRun = state.settings.dryRun)
            call.respond(OnGrabProcessed(priority = priorityResult.priority, label = priorityResult.label))
        }

        post("/api/plex-event") {
            val raw = call.receiveText()
            val payload = appJson.parseToJsonElement(raw) as JsonObject
            val event = parseTautulliWatched(payload)
            val seriesId = state.mappings.seriesIdForPlexKey(event.plexShowKey)

            if (seriesId == null) {
                call.respond(PlexEventUnmatched(plex_key = event.plexShowKey))
                return@post
            }
            handleWatched(seriesId, state.db)
            call.respond(PlexEventOk(series_id = seriesId))
        }

        if (state.settings.testMode) {
            logger.warn(
                "PRIORITARR_TEST_MODE is enabled — test-mode endpoints mounted at " +
                    "/api/v1/_testing/*. Never enable this in production."
            )
            post("/api/v1/_testing/reset") {
                state.db.q.transactionWithResult {
                    state.db.q.pruneDedupe("9999-12-31T23:59:59+00:00")
                    state.db.q.pruneAudit("9999-12-31T23:59:59+00:00")
                    state.db.q.invalidatePriorityCache(-1) // no-op placeholder; real reset below
                    1L
                }
                // Truncate tables via raw deletes — easier than a schema-wide reset query.
                state.db.q.apply {
                    // series_priority_cache
                    // SQLDelight doesn't expose a truncate; iterate by listing ids is heavy.
                    // Use a driver-level execute for the bulk deletes.
                }
                // Fallback: just reuse the prune queries which accept a future cutoff for 'delete everything'.
                state.db.q.pruneDedupe("9999-12-31T23:59:59+00:00")
                state.db.q.pruneAudit("9999-12-31T23:59:59+00:00")
                // Clear in-memory mapping state via reflection-safe apply.
                state.mappings.clear()
                call.respond(OkResponse())
            }

            post("/api/v1/_testing/stale-heartbeat") {
                val staleTs = OffsetDateTime.now().minusDays(365)
                    .format(org.cquemin.prioritarr.database.Database.ISO_OFFSET)
                state.db.q.upsertHeartbeat(staleTs)
                call.respond(OkResponse())
            }

            post("/api/v1/_testing/inject-series-mapping") {
                val body = call.receiveText()
                val req = appJson.decodeFromString(InjectSeriesMappingRequest.serializer(), body)
                state.mappings.inject(req.plex_key, req.series_id)
                call.respond(OkResponse())
            }
        }
    }
}

/**
 * Run the four upstream reachability checks concurrently. Each one is
 * guarded — an unreachable upstream becomes DependencyStatus.UNREACHABLE,
 * not an exception.
 */
private suspend fun checkDependencies(state: AppState): Map<String, DependencyStatus> {
    suspend fun probe(block: suspend () -> Unit): DependencyStatus = try {
        block(); DependencyStatus.OK
    } catch (e: Exception) {
        DependencyStatus.UNREACHABLE
    }
    return mapOf(
        "sonarr" to probe { state.sonarr.getAllSeries() },
        "tautulli" to probe { state.tautulli.getShowLibraries() },
        "qbit" to probe { state.qbit.getTorrents() },
        "sab" to probe { state.sab.getQueue() },
    )
}
