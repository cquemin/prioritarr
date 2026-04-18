package org.yoshiz.app.prioritarr.backend.api.v2

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.yoshiz.app.prioritarr.backend.app.AppState
import org.yoshiz.app.prioritarr.backend.app.appJson
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.errors.NotFoundException
import org.yoshiz.app.prioritarr.backend.errors.UpstreamUnreachableException
import org.yoshiz.app.prioritarr.backend.errors.ValidationException
import org.yoshiz.app.prioritarr.backend.mapping.refreshMappings
import org.yoshiz.app.prioritarr.backend.pagination.PaginatedEnvelope
import org.yoshiz.app.prioritarr.backend.pagination.pageParamsFrom
import org.yoshiz.app.prioritarr.backend.pagination.paginate
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import org.yoshiz.app.prioritarr.backend.schemas.ActionResult
import org.yoshiz.app.prioritarr.backend.schemas.AuditEntry
import org.yoshiz.app.prioritarr.backend.schemas.InjectSeriesMappingRequest
import org.yoshiz.app.prioritarr.backend.schemas.ManagedDownloadWire
import org.yoshiz.app.prioritarr.backend.schemas.MappingSnapshot
import org.yoshiz.app.prioritarr.backend.schemas.PriorityResultWire
import org.yoshiz.app.prioritarr.backend.schemas.SeriesDetail
import org.yoshiz.app.prioritarr.backend.schemas.SeriesSummary
import org.yoshiz.app.prioritarr.backend.schemas.SettingsRedacted

private fun PriorityResult.toWire() = PriorityResultWire(priority, label, reason)

private fun redactSecret(v: String?): String? = if (v.isNullOrBlank()) v else "***"

fun Route.v2Routes(state: AppState) {
    // ---- SSE ----
    eventsRoute(state)

    // ---- Stats ----
    statsRoute(state)

    // ---- Reads ----

    route("/series") {
        get {
            val params = pageParamsFrom(call, allowedSorts = setOf("priority", "title", "id"), defaultSort = "priority")
            val all = state.sonarr.getAllSeries()
            val rows = all.map { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@map null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val tvdbId = obj["tvdbId"]?.jsonPrimitive?.longOrNull
                val cache = state.db.getPriorityCache(id)
                SeriesSummary(
                    id = id,
                    title = title,
                    tvdbId = tvdbId,
                    priority = cache?.priority?.toInt(),
                    label = cache?.let { "P${it.priority}" },
                    computedAt = cache?.computed_at,
                    managedDownloadCount = state.db.listManagedDownloads().count { it.series_id == id },
                )
            }.filterNotNull()

            val sorted = when (params.sort) {
                "title" -> rows.sortedBy { it.title }
                "id" -> rows.sortedBy { it.id }
                else -> rows.sortedBy { it.priority ?: Int.MAX_VALUE }
            }.let { if (params.sortDir == org.yoshiz.app.prioritarr.backend.pagination.SortDir.DESC) it.reversed() else it }

            call.respond(paginate(sorted, params))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("id", "must be a number")
            val seriesObj = try {
                state.sonarr.getSeries(id)
            } catch (e: Exception) {
                throw NotFoundException("series $id not found in Sonarr")
            }
            // Some upstream stacks (incl. the WireMock stub) return 200
            // with an arbitrary body for unknown ids. Validate the returned
            // id matches what we asked for, else treat as not found.
            val returnedId = seriesObj["id"]?.jsonPrimitive?.longOrNull
            if (returnedId != id) throw NotFoundException("series $id not found in Sonarr")
            val title = seriesObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tvdbId = seriesObj["tvdbId"]?.jsonPrimitive?.longOrNull
            val cache = state.db.getPriorityCache(id)
            val summary = SeriesSummary(
                id = id,
                title = title,
                tvdbId = tvdbId,
                priority = cache?.priority?.toInt(),
                label = cache?.let { "P${it.priority}" },
                computedAt = cache?.computed_at,
                managedDownloadCount = state.db.listManagedDownloads().count { it.series_id == id },
            )
            val priority = cache?.let {
                PriorityResultWire(it.priority.toInt(), "P${it.priority}", it.reason.orEmpty())
            }
            call.respond(SeriesDetail(
                summary = summary,
                priority = priority,
                cacheExpiresAt = cache?.expires_at,
                recentAudit = state.db.listAudit(seriesId = id, limit = 10),
            ))
        }

        post("/{id}/recompute") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("id", "must be a number")
            val existing = try {
                state.sonarr.getSeries(id)
            } catch (e: Exception) {
                throw NotFoundException("series $id not found in Sonarr")
            }
            if (existing["id"]?.jsonPrimitive?.longOrNull != id) {
                throw NotFoundException("series $id not found in Sonarr")
            }
            if (!state.settings.dryRun) {
                state.db.invalidatePriorityCache(id)
            }
            val result = state.priorityService.priorityForSeries(id)
            state.eventBus.publish("priority-recomputed", Json.encodeToJsonElement(
                PriorityResultWire.serializer(), result.toWire()
            ))
            call.respond(ActionResult(
                ok = true,
                dryRun = state.settings.dryRun,
                priority = result.toWire(),
                message = if (state.settings.dryRun) "dry-run: cache not cleared" else null,
            ))
        }
    }

    // ---- Downloads ----

    route("/downloads") {
        get {
            val params = pageParamsFrom(call, allowedSorts = setOf("clientId", "seriesId", "lastReconciledAt"), defaultSort = "lastReconciledAt", defaultDir = org.yoshiz.app.prioritarr.backend.pagination.SortDir.DESC)
            val clientFilter = call.request.queryParameters["client"]
            val rows = state.db.listManagedDownloads(clientFilter).map { row ->
                val title = try {
                    state.sonarr.getSeries(row.series_id)["title"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { null }
                ManagedDownloadWire(
                    client = row.client,
                    clientId = row.client_id,
                    seriesId = row.series_id,
                    seriesTitle = title,
                    episodeIds = parseEpisodeIds(row.episode_ids),
                    initialPriority = row.initial_priority.toInt(),
                    currentPriority = row.current_priority.toInt(),
                    pausedByUs = row.paused_by_us == 1L,
                    firstSeenAt = row.first_seen_at,
                    lastReconciledAt = row.last_reconciled_at,
                )
            }
            val sorted = when (params.sort) {
                "clientId" -> rows.sortedBy { it.clientId }
                "seriesId" -> rows.sortedBy { it.seriesId }
                else -> rows.sortedBy { it.lastReconciledAt }
            }.let { if (params.sortDir == org.yoshiz.app.prioritarr.backend.pagination.SortDir.DESC) it.reversed() else it }
            call.respond(paginate(sorted, params))
        }

        get("/{client}/{clientId}") {
            val client = call.parameters["client"]!!
            val clientId = call.parameters["clientId"]!!
            val row = state.db.getManagedDownload(client, clientId)
                ?: throw NotFoundException("managed_download $client/$clientId not found")
            val title = try {
                state.sonarr.getSeries(row.series_id)["title"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            call.respond(ManagedDownloadWire(
                client = row.client,
                clientId = row.client_id,
                seriesId = row.series_id,
                seriesTitle = title,
                episodeIds = parseEpisodeIds(row.episode_ids),
                initialPriority = row.initial_priority.toInt(),
                currentPriority = row.current_priority.toInt(),
                pausedByUs = row.paused_by_us == 1L,
                firstSeenAt = row.first_seen_at,
                lastReconciledAt = row.last_reconciled_at,
            ))
        }

        delete("/{client}/{clientId}") {
            val client = call.parameters["client"]!!
            val clientId = call.parameters["clientId"]!!
            if (state.db.getManagedDownload(client, clientId) == null) {
                throw NotFoundException("managed_download $client/$clientId not found")
            }
            state.db.deleteManagedDownload(client, clientId)
            state.eventBus.publish(
                "download-untracked",
                Json.parseToJsonElement("""{"client":"$client","client_id":"$clientId"}"""),
            )
            call.respond(ActionResult(ok = true, message = "untracked"))
        }

        // ---- Actions ----
        post("/{client}/{clientId}/actions/{action}") {
            val client = call.parameters["client"]!!
            val clientId = call.parameters["clientId"]!!
            val action = call.parameters["action"]!!
            val row = state.db.getManagedDownload(client, clientId)
                ?: throw NotFoundException("managed_download $client/$clientId not found")

            if (action == "pause" && row.paused_by_us == 1L) {
                call.respond(ActionResult(ok = true, alreadyPaused = true))
                return@post
            }

            if (state.settings.dryRun) {
                state.db.appendAudit(
                    action = "dry_run_action",
                    seriesId = row.series_id,
                    client = client,
                    clientId = clientId,
                    details = Json.parseToJsonElement("""{"requested_action":"$action"}"""),
                )
                call.respond(ActionResult(ok = true, dryRun = true, message = action))
                return@post
            }

            when (client) {
                "qbit" -> when (action) {
                    "pause" -> state.qbit.pause(listOf(clientId))
                    "resume" -> state.qbit.resume(listOf(clientId))
                    "boost" -> state.qbit.topPriority(listOf(clientId))
                    "demote" -> state.qbit.bottomPriority(listOf(clientId))
                    else -> throw ValidationException("action", "must be pause|resume|boost|demote")
                }
                "sab" -> {
                    val sabPriority = when (action) {
                        "pause" -> SABClient.PRIORITY_MAP[5] ?: -1
                        "resume" -> SABClient.PRIORITY_MAP[3] ?: 0
                        "boost" -> 2 // Force
                        "demote" -> -1 // Low
                        else -> throw ValidationException("action", "must be pause|resume|boost|demote")
                    }
                    try {
                        state.sab.setPriority(clientId, sabPriority)
                    } catch (e: Exception) {
                        throw UpstreamUnreachableException("sab", e.message.orEmpty())
                    }
                }
                else -> throw ValidationException("client", "must be qbit or sab, got $client")
            }

            if (action == "pause") {
                state.db.upsertManagedDownload(
                    client = row.client,
                    clientId = row.client_id,
                    seriesId = row.series_id,
                    episodeIds = parseEpisodeIds(row.episode_ids),
                    initialPriority = row.initial_priority,
                    currentPriority = row.current_priority,
                    pausedByUs = true,
                    firstSeenAt = row.first_seen_at,
                    lastReconciledAt = org.yoshiz.app.prioritarr.backend.database.Database.nowIsoOffset(),
                )
            }
            state.eventBus.publish(
                "download-action",
                Json.parseToJsonElement("""{"client":"$client","client_id":"$clientId","action":"$action"}"""),
            )
            call.respond(ActionResult(ok = true, message = action))
        }
    }

    // ---- Audit ----

    get("/audit") {
        val params = pageParamsFrom(call, allowedSorts = setOf("ts", "id"), defaultSort = "ts",
            defaultDir = org.yoshiz.app.prioritarr.backend.pagination.SortDir.DESC)
        val seriesId = call.request.queryParameters["series_id"]?.toLongOrNull()
        val action = call.request.queryParameters["action"]
        val since = call.request.queryParameters["since"]
        val rows = state.db.listAudit(seriesId = seriesId, action = action, since = since, limit = 10_000)
        call.respond(paginate(rows, params))
    }

    // ---- Settings (redacted) ----

    get("/settings") {
        val s = state.settings
        call.respond(SettingsRedacted(
            sonarrUrl = s.sonarrUrl,
            sonarrApiKey = "***",
            tautulliUrl = s.tautulliUrl,
            tautulliApiKey = "***",
            qbitUrl = s.qbitUrl,
            qbitUsername = s.qbitUsername,
            qbitPassword = redactSecret(s.qbitPassword),
            sabUrl = s.sabUrl,
            sabApiKey = "***",
            plexUrl = s.plexUrl,
            plexToken = redactSecret(s.plexToken),
            redisUrl = redactSecret(s.redisUrl),
            apiKey = redactSecret(s.apiKey),
            uiOrigin = s.uiOrigin,
            dryRun = s.dryRun,
            logLevel = s.logLevel,
            testMode = s.testMode,
        ))
    }

    // ---- Mappings ----

    get("/mappings") {
        call.respond(MappingSnapshot(
            plexKeyToSeriesId = state.mappings.plexKeyToSeriesId,
            lastRefreshStats = state.mappings.lastRefreshStats,
            tautulliAvailable = state.mappings.tautulliAvailable,
        ))
    }

    post("/mappings/refresh") {
        // Use an in-memory cache for the ad-hoc refresh to avoid stomping on
        // the production Redis during dry-run. In non-dry-run mode, the
        // scheduler's periodic refresh uses the real cache; this endpoint is
        // intentionally side-effect-light.
        val cache = org.yoshiz.app.prioritarr.backend.mapping.InMemoryMappingCache()
        val stats = refreshMappings(state.sonarr, state.tautulli, cache, state.mappings)
        state.eventBus.publish(
            "mapping-refreshed",
            Json.encodeToJsonElement(org.yoshiz.app.prioritarr.backend.mapping.RefreshStats.serializer(), stats),
        )
        call.respond(ActionResult(ok = true, refreshStats = stats))
    }
}

private fun parseEpisodeIds(raw: String?): List<Long> =
    raw?.let {
        try {
            Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.long }
        } catch (_: Exception) { emptyList() }
    } ?: emptyList()

private val kotlinx.serialization.json.JsonPrimitive.long: Long get() = this.content.toLong()
