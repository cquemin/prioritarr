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
import org.yoshiz.app.prioritarr.backend.schemas.BulkActionResult
import org.yoshiz.app.prioritarr.backend.schemas.BulkDownloadActionRequest
import org.yoshiz.app.prioritarr.backend.schemas.BulkItemResult
import org.yoshiz.app.prioritarr.backend.schemas.ExternalLinks
import org.yoshiz.app.prioritarr.backend.schemas.InjectSeriesMappingRequest
import org.yoshiz.app.prioritarr.backend.schemas.ManagedDownloadWire
import org.yoshiz.app.prioritarr.backend.schemas.MappingSnapshot
import org.yoshiz.app.prioritarr.backend.schemas.PriorityResultWire
import org.yoshiz.app.prioritarr.backend.schemas.LibrarySyncReport
import org.yoshiz.app.prioritarr.backend.schemas.SeriesDetail
import org.yoshiz.app.prioritarr.backend.schemas.SeriesSummary
import org.yoshiz.app.prioritarr.backend.schemas.SeriesSyncReport
import org.yoshiz.app.prioritarr.backend.schemas.SettingsRedacted
import io.ktor.server.request.receive

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
            // Read-model: series list comes straight from local series_cache
            // (populated by the refreshSeriesCache background job). Pure SQL +
            // in-memory map lookups, no upstream calls in the request path.
            val all = state.db.q.listSeriesCache().executeAsList()
            val allDownloads = state.db.listManagedDownloads()
            val downloadsBySeries: Map<Long, List<org.yoshiz.app.prioritarr.backend.database.Managed_downloads>> =
                allDownloads.groupBy { it.series_id }
            val cacheBySeries = state.db.q.listPriorityCache()
                .executeAsList()
                .associateBy { it.series_id }
            val rows = all.map { row ->
                val cache = cacheBySeries[row.id]
                val dls = downloadsBySeries[row.id].orEmpty()
                SeriesSummary(
                    id = row.id,
                    title = row.title,
                    titleSlug = null,  // filled only by the detail endpoint (cheaper this way)
                    tvdbId = row.tvdb_id,
                    priority = cache?.priority?.toInt(),
                    label = cache?.let { "P${it.priority}" },
                    reason = cache?.reason,
                    computedAt = cache?.computed_at,
                    managedDownloadCount = dls.size,
                    clients = dls.map { it.client }.distinct().sorted(),
                    pausedCount = dls.count { it.paused_by_us == 1L },
                )
            }

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
            val titleSlug = seriesObj["titleSlug"]?.jsonPrimitive?.contentOrNull
            val tvdbId = seriesObj["tvdbId"]?.jsonPrimitive?.longOrNull
            val cache = state.db.getPriorityCache(id)
            val managed = state.db.listManagedDownloads().filter { it.series_id == id }

            val downloadWires = managed.map { row ->
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

            val summary = SeriesSummary(
                id = id,
                title = title,
                titleSlug = titleSlug,
                tvdbId = tvdbId,
                priority = cache?.priority?.toInt(),
                label = cache?.let { "P${it.priority}" },
                reason = cache?.reason,
                computedAt = cache?.computed_at,
                managedDownloadCount = managed.size,
                clients = managed.map { it.client }.distinct().sorted(),
                pausedCount = managed.count { it.paused_by_us == 1L },
            )
            val priority = cache?.let {
                PriorityResultWire(it.priority.toInt(), "P${it.priority}", it.reason.orEmpty())
            }
            val plexKey = state.mappings.plexKeyToSeriesId.entries
                .firstOrNull { it.value == id }?.key
            val links = buildExternalLinks(
                origin = state.settings.uiOrigin,
                titleSlug = titleSlug,
                tvdbId = tvdbId,
                plexKey = plexKey,
                hasQbit = managed.any { it.client == "qbit" },
                hasSab = managed.any { it.client == "sab" },
            )
            call.respond(SeriesDetail(
                summary = summary,
                priority = priority,
                cacheExpiresAt = cache?.expires_at,
                recentAudit = state.db.listAudit(seriesId = id, limit = 10),
                downloads = downloadWires,
                externalLinks = links,
            ))
        }

        // Per-series cross-source sync. Symmetric: pushes any episodes
        // Plex has watched but Trakt doesn't (and vice versa) so both
        // sides agree on the watch set. Idempotent — re-running is a
        // no-op once both sides converge.
        post("/{id}/sync") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("id", "must be a number")
            val report = state.crossSourceSync.syncSeries(id, dryRun = state.settings.dryRun)
            state.eventBus.publish(
                "series-synced",
                Json.encodeToJsonElement(SeriesSyncReport.serializer(), report),
            )
            call.respond(report)
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
            // Title lookup served from local series_cache — no upstream call.
            val titleById: Map<Long, String> = state.db.q.listSeriesCache()
                .executeAsList()
                .associate { it.id to it.title }
            val rows = state.db.listManagedDownloads(clientFilter).map { row ->
                ManagedDownloadWire(
                    client = row.client,
                    clientId = row.client_id,
                    seriesId = row.series_id,
                    seriesTitle = titleById[row.series_id],
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

        // ---- Bulk action ----
        //
        // Sequential per-item (small selections, clients don't love
        // parallel bursts). Partial failures surface as per-item
        // `ok=false` — the HTTP response is always 200 with a detailed
        // body the UI can render as a mixed-outcome toast.
        post("/bulk") {
            val req = try {
                call.receive<BulkDownloadActionRequest>()
            } catch (e: Exception) {
                throw ValidationException("body", "must be {action, items: [{client, clientId}, ...]}: ${e.message}")
            }
            if (req.items.isEmpty()) {
                throw ValidationException("items", "must contain at least one {client, clientId}")
            }
            if (req.action !in setOf("pause", "resume", "boost", "demote", "untrack")) {
                throw ValidationException("action", "must be pause|resume|boost|demote|untrack")
            }

            val results = req.items.map { ref ->
                applyDownloadAction(state, ref.client, ref.clientId, req.action)
            }
            val succeeded = results.count { it.ok }
            val failed = results.size - succeeded
            call.respond(BulkActionResult(
                ok = failed == 0,
                dryRun = state.settings.dryRun,
                total = results.size,
                succeeded = succeeded,
                failed = failed,
                results = results,
            ))
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

    // ---- Library-wide cross-source sync ----
    //
    // Walks every series in the local series_cache and runs the same
    // per-series sync. Sequential (no parallelism) to keep upstream
    // pressure low on Plex + Trakt; the typical library is ~150 series
    // and the inner sync is ~3 HTTP calls each, so ~10 minutes worst
    // case. Returns one aggregate report with per-series breakdown.
    post("/sync") {
        val all = state.db.q.listSeriesCache().executeAsList()
        val perSeries = mutableListOf<SeriesSyncReport>()
        var plexTotal = 0
        var traktTotal = 0
        for (row in all) {
            val r = state.crossSourceSync.syncSeries(row.id, dryRun = state.settings.dryRun)
            perSeries += r
            plexTotal += r.plexAdded
            traktTotal += r.traktAdded
        }
        val agg = LibrarySyncReport(
            ok = perSeries.all { it.errors.isEmpty() && it.skippedReason == null },
            dryRun = state.settings.dryRun,
            totalSeries = perSeries.size,
            plexAddedTotal = plexTotal,
            traktAddedTotal = traktTotal,
            perSeries = perSeries,
        )
        state.eventBus.publish(
            "library-synced",
            Json.encodeToJsonElement(LibrarySyncReport.serializer(), agg),
        )
        call.respond(agg)
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

/**
 * Compute deep-link URLs for a series across every third-party tool
 * we know about. Each field is nullable — if the ingredients for a
 * particular URL aren't available (no plex_key, no titleSlug, no
 * origin), that field stays null and the UI just doesn't render a
 * link for it.
 *
 * This is deliberately string-construction, not a full URL-parse
 * round-trip — the deploy-local Traefik routes (/sonarr, /tautulli,
 * /qbittorrent, /sabnzbd) are stable across prioritarr's user base,
 * and Plex/Trakt public URL shapes are documented API patterns.
 */
private fun buildExternalLinks(
    origin: String?,
    titleSlug: String?,
    tvdbId: Long?,
    plexKey: String?,
    hasQbit: Boolean,
    hasSab: Boolean,
): ExternalLinks {
    val base = origin?.trimEnd('/')
    return ExternalLinks(
        sonarr = if (base != null && titleSlug != null) "$base/sonarr/series/$titleSlug" else null,
        // Trakt's "by TVDB" permalink redirects to the show's canonical page.
        trakt = tvdbId?.let { "https://trakt.tv/search/tvdb/$it?id_type=show" },
        // Tautulli doesn't accept a query-string deep link to a specific
        // media item; users land on the library index and click through.
        // Surface the rating_key in the URL so a future Tautulli release
        // that supports it lights up automatically.
        tautulli = if (base != null && plexKey != null) "$base/tautulli/#?rating_key=$plexKey" else null,
        // Plex web deep links need the server's machine id which we
        // don't track; the subdomain form resolves in most deployments.
        plex = base?.let { it.replace("://", "://plex.").replaceAfter(".", "/") + "web/index.html" }
            ?.takeIf { false }, // disabled until we expose Plex machine id
        qbit = if (base != null && hasQbit) "$base/qbittorrent/" else null,
        sab = if (base != null && hasSab) "$base/sabnzbd/" else null,
    )
}

private fun parseEpisodeIds(raw: String?): List<Long> =
    raw?.let {
        try {
            Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.long }
        } catch (_: Exception) { emptyList() }
    } ?: emptyList()

private val kotlinx.serialization.json.JsonPrimitive.long: Long get() = this.content.toLong()

/**
 * Apply a single-item download action. Used by the bulk endpoint; the
 * existing single-item route throws exceptions directly and is left
 * untouched to avoid churn in its error shape.
 *
 * Returns an outcome instead of throwing — in a bulk batch we want a
 * per-item verdict even when some fail. Dry-run is encoded the same
 * way the single-item path does it (audit entry + ok=true).
 */
private suspend fun applyDownloadAction(
    state: AppState,
    client: String,
    clientId: String,
    action: String,
): BulkItemResult {
    if (action == "untrack") {
        if (state.db.getManagedDownload(client, clientId) == null) {
            return BulkItemResult(client, clientId, ok = false, message = "not tracked")
        }
        state.db.deleteManagedDownload(client, clientId)
        state.eventBus.publish(
            "download-untracked",
            Json.parseToJsonElement("""{"client":"$client","client_id":"$clientId"}"""),
        )
        return BulkItemResult(client, clientId, ok = true, message = "untracked")
    }

    val row = state.db.getManagedDownload(client, clientId)
        ?: return BulkItemResult(client, clientId, ok = false, message = "not tracked")

    if (action == "pause" && row.paused_by_us == 1L) {
        return BulkItemResult(client, clientId, ok = true, message = "already paused")
    }

    if (state.settings.dryRun) {
        state.db.appendAudit(
            action = "dry_run_action",
            seriesId = row.series_id,
            client = client,
            clientId = clientId,
            details = Json.parseToJsonElement("""{"requested_action":"$action"}"""),
        )
        return BulkItemResult(client, clientId, ok = true, message = "dry-run: $action")
    }

    try {
        when (client) {
            "qbit" -> when (action) {
                "pause" -> state.qbit.pause(listOf(clientId))
                "resume" -> state.qbit.resume(listOf(clientId))
                "boost" -> state.qbit.topPriority(listOf(clientId))
                "demote" -> state.qbit.bottomPriority(listOf(clientId))
                else -> return BulkItemResult(client, clientId, ok = false, message = "unknown action: $action")
            }
            "sab" -> {
                val sabPriority = when (action) {
                    "pause" -> SABClient.PRIORITY_MAP[5] ?: -1
                    "resume" -> SABClient.PRIORITY_MAP[3] ?: 0
                    "boost" -> 2 // Force
                    "demote" -> -1 // Low
                    else -> return BulkItemResult(client, clientId, ok = false, message = "unknown action: $action")
                }
                state.sab.setPriority(clientId, sabPriority)
            }
            else -> return BulkItemResult(client, clientId, ok = false, message = "unknown client: $client")
        }
    } catch (e: Exception) {
        return BulkItemResult(client, clientId, ok = false, message = "upstream: ${e.message}")
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
    return BulkItemResult(client, clientId, ok = true, message = action)
}
