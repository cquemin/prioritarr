package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.request.get
import kotlinx.coroutines.async
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.statuspages.StatusPages
import org.yoshiz.app.prioritarr.backend.auth.apiKey
import org.yoshiz.app.prioritarr.backend.errors.InternalException
import org.yoshiz.app.prioritarr.backend.errors.PrioritarrException
import org.yoshiz.app.prioritarr.backend.errors.respondProblem
import org.yoshiz.app.prioritarr.backend.errors.toProblem
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
import org.yoshiz.app.prioritarr.backend.health.checkLiveness
import org.yoshiz.app.prioritarr.backend.health.checkReadiness
import org.yoshiz.app.prioritarr.backend.schemas.DependencyStatus
import org.yoshiz.app.prioritarr.backend.schemas.HealthOk
import org.yoshiz.app.prioritarr.backend.schemas.HealthUnhealthy
import org.yoshiz.app.prioritarr.backend.schemas.InjectSeriesMappingRequest
import org.yoshiz.app.prioritarr.backend.schemas.OkResponse
import org.yoshiz.app.prioritarr.backend.schemas.OnGrabIgnored
import org.yoshiz.app.prioritarr.backend.schemas.OnGrabProcessed
import org.yoshiz.app.prioritarr.backend.schemas.OnGrabDuplicate
import org.yoshiz.app.prioritarr.backend.schemas.PlexEventOk
import org.yoshiz.app.prioritarr.backend.schemas.PlexEventUnmatched
import org.yoshiz.app.prioritarr.backend.webhooks.eventKey
import org.yoshiz.app.prioritarr.backend.webhooks.handleOnGrab
import org.yoshiz.app.prioritarr.backend.webhooks.handleWatched
import org.yoshiz.app.prioritarr.backend.webhooks.parseOnGrabPayload
import org.yoshiz.app.prioritarr.backend.webhooks.parseTautulliWatched
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

val appJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.app")

fun Application.prioritarrModule(state: AppState) {
    install(ContentNegotiation) { json(appJson) }

    // Spec §5.3 "always-200 webhook" shield — any unhandled exception on
    // webhook paths is downgraded to a 200 JSON body. Health/ready/testing
    // retain normal error semantics.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val path = call.request.path()
            when {
                // Spec A §5.3 always-200 webhooks — never emit an error body.
                path == "/api/sonarr/on-grab" -> {
                    logger.warn("sonarr on-grab webhook exception: ${cause.message}")
                    call.respond(OnGrabIgnored(eventType = "unknown"))
                }
                path == "/api/plex-event" -> {
                    logger.warn("plex-event webhook exception: ${cause.message}")
                    call.respond(PlexEventUnmatched(plex_key = ""))
                }
                // Spec C §6 — /api/v2/* errors use RFC 7807 Problem Details.
                cause is PrioritarrException -> {
                    call.respondProblem(cause.toProblem(path), cause.httpStatus)
                }
                path.startsWith("/api/v2/") -> {
                    logger.error("unhandled v2 exception on $path", cause)
                    call.respondProblem(
                        InternalException(cause.message.orEmpty()).toProblem(path),
                        HttpStatusCode.InternalServerError,
                    )
                }
                else -> call.respondText(
                    """{"detail":"${cause.message?.replace("\"", "'")}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
    }

    // Spec C §5 — X-Api-Key auth, applied only to /api/v2/* in routing{}.
    install(Authentication) {
        apiKey("api_key") {
            expectedKey = state.settings.apiKey
        }
    }
    if (state.settings.apiKey == null) {
        logger.warn("PRIORITARR_API_KEY is not set — /api/v2/* endpoints will accept all requests.")
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
                state.db.resetAllState()
                state.mappings.clear()
                call.respond(OkResponse())
            }

            post("/api/v1/_testing/stale-heartbeat") {
                val staleTs = OffsetDateTime.now().minusDays(365)
                    .format(org.yoshiz.app.prioritarr.backend.database.Database.ISO_OFFSET)
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
 * TCP-only probe client with 2s connect+read timeouts. Used by /ready
 * to avoid inheriting the 120s timeouts of the main Sonarr/Tautulli
 * clients. Only the fact that /some-url responds is interesting, not
 * the body.
 */
private val probeClient by lazy {
    io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 2_000
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 2_000
        }
        expectSuccess = false
    }
}

/**
 * Four upstream reachability probes in parallel, 2s max each.
 * A probe succeeds if the upstream answers with *any* HTTP response
 * (2xx, 3xx, 4xx all count as "reachable"). 5xx or no response →
 * UNREACHABLE.
 */
private suspend fun checkDependencies(state: AppState): Map<String, DependencyStatus> = kotlinx.coroutines.coroutineScope scope@{
    suspend fun probe(url: String): DependencyStatus = try {
        val resp = probeClient.get(url)
        if (resp.status.value < 500) DependencyStatus.OK else DependencyStatus.UNREACHABLE
    } catch (e: Exception) {
        DependencyStatus.UNREACHABLE
    }
    val sonarr = async { probe(state.settings.sonarrUrl.trimEnd('/') + "/api/v3/system/status") }
    val tautulli = async { probe(state.settings.tautulliUrl.trimEnd('/') + "/api/v2?apikey=${state.settings.tautulliApiKey}&cmd=arnold") }
    val qbit = async { probe(state.settings.qbitUrl.trimEnd('/') + "/api/v2/app/version") }
    val sab = async { probe(state.settings.sabUrl.trimEnd('/') + "/sabnzbd/api?mode=version&output=json&apikey=${state.settings.sabApiKey}") }
    mapOf(
        "sonarr" to sonarr.await(),
        "tautulli" to tautulli.await(),
        "qbit" to qbit.await(),
        "sab" to sab.await(),
    )
}
