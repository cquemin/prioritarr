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
import org.yoshiz.app.prioritarr.backend.schemas.ManagedDownloadPreview
import org.yoshiz.app.prioritarr.backend.schemas.MatchedEpisode
import org.yoshiz.app.prioritarr.backend.schemas.OrphanAuditRow
import org.yoshiz.app.prioritarr.backend.schemas.OrphanBulkResult
import org.yoshiz.app.prioritarr.backend.schemas.OrphanPathOutcome
import org.yoshiz.app.prioritarr.backend.schemas.OrphanPathsRequest
import org.yoshiz.app.prioritarr.backend.schemas.OrphanProbeResult
import org.yoshiz.app.prioritarr.backend.schemas.OrphanRenameRequest
import org.yoshiz.app.prioritarr.backend.schemas.OrphanRenameResult
import org.yoshiz.app.prioritarr.backend.schemas.PriorityPreviewEntry
import org.yoshiz.app.prioritarr.backend.schemas.PriorityPreviewRequest
import org.yoshiz.app.prioritarr.backend.schemas.PriorityPreviewResponse
import org.yoshiz.app.prioritarr.backend.schemas.SearchHit
import org.yoshiz.app.prioritarr.backend.schemas.SearchResponse
import org.yoshiz.app.prioritarr.backend.schemas.SeriesDetail
import org.yoshiz.app.prioritarr.backend.schemas.SeriesSummary
import org.yoshiz.app.prioritarr.backend.schemas.SeriesSyncReport
import org.yoshiz.app.prioritarr.backend.schemas.SettingsRedacted
import io.ktor.server.request.receive

private fun PriorityResult.toWire() = PriorityResultWire(priority, label, reason)

private fun redactSecret(v: String?): String? = if (v.isNullOrBlank()) v else "***"

/**
 * Merge a patch onto the existing override blob. For each field, the
 * patch value wins if non-null; otherwise the existing value (which
 * may itself be null) is kept. This is what lets the UI send only the
 * fields the user actually edited without nuking the rest.
 */
private fun mergeEditable(
    existing: org.yoshiz.app.prioritarr.backend.config.EditableSettings,
    patch: org.yoshiz.app.prioritarr.backend.config.EditableSettings,
): org.yoshiz.app.prioritarr.backend.config.EditableSettings = existing.copy(
    sonarrUrl = patch.sonarrUrl ?: existing.sonarrUrl,
    sonarrApiKey = patch.sonarrApiKey ?: existing.sonarrApiKey,
    tautulliUrl = patch.tautulliUrl ?: existing.tautulliUrl,
    tautulliApiKey = patch.tautulliApiKey ?: existing.tautulliApiKey,
    qbitUrl = patch.qbitUrl ?: existing.qbitUrl,
    qbitUsername = patch.qbitUsername ?: existing.qbitUsername,
    qbitPassword = patch.qbitPassword ?: existing.qbitPassword,
    sabUrl = patch.sabUrl ?: existing.sabUrl,
    sabApiKey = patch.sabApiKey ?: existing.sabApiKey,
    plexUrl = patch.plexUrl ?: existing.plexUrl,
    plexToken = patch.plexToken ?: existing.plexToken,
    traktClientId = patch.traktClientId ?: existing.traktClientId,
    traktAccessToken = patch.traktAccessToken ?: existing.traktAccessToken,
    dryRun = patch.dryRun ?: existing.dryRun,
    logLevel = patch.logLevel ?: existing.logLevel,
    uiOrigin = patch.uiOrigin ?: existing.uiOrigin,
)

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

        // Per-provider watch status — how many episodes each watch
        // source (tautulli / plex / trakt) thinks are watched, plus
        // latest watched timestamp. Drives the drawer's watch-status
        // table and the sync-button "needed vs. in sync" decision.
        get("/{id}/watch-status") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("id", "must be a number")
            val statuses = state.priorityService.perProviderWatchStatus(id)
            call.respond(statuses)
        }

        // Per-series cross-source sync. Symmetric: pushes any episodes
        // Plex has watched but Trakt doesn't (and vice versa) so both
        // sides agree on the watch set. Idempotent — re-running is a
        // no-op once both sides converge.
        //
        // ?dryRun=true forces a non-destructive run regardless of the
        // global setting — the report still enumerates everything that
        // would be pushed, but no scrobble / sync/history call fires.
        post("/{id}/sync") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("id", "must be a number")
            val dryRun = call.request.queryParameters["dryRun"]?.equals("true", ignoreCase = true)
                ?: state.settings.dryRun
            val report = state.crossSourceSync.syncSeries(id, dryRun = dryRun)
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

            // Dispatch via the DownloadClient registry so adding a
            // third downloader (Transmission/NZBGet) doesn't require
            // touching this switch — new clients register themselves
            // in AppState.downloadClients and flow through here.
            val dc = state.downloadClients[client]
                ?: throw ValidationException("client", "unknown client '$client' (known: ${state.downloadClients.keys.joinToString()})")
            try {
                when (action) {
                    "pause" -> dc.pauseOne(clientId)
                    "resume" -> dc.resumeOne(clientId)
                    "boost" -> dc.boostOne(clientId)
                    "demote" -> dc.demoteOne(clientId)
                    else -> throw ValidationException("action", "must be pause|resume|boost|demote")
                }
            } catch (e: ValidationException) { throw e }
            catch (e: Exception) {
                throw UpstreamUnreachableException(client, e.message.orEmpty())
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
            traktClientId = s.traktClientId,
            traktAccessToken = redactSecret(s.traktAccessToken),
            apiKey = redactSecret(s.apiKey),
            uiOrigin = s.uiOrigin,
            dryRun = s.dryRun,
            logLevel = s.logLevel,
            testMode = s.testMode,
            hasOverrides = state.db.getSettingsOverride() != null,
        ))
    }

    // Persist editable settings (URLs / creds / dryRun / logLevel /
    // uiOrigin). Stored as a JSON-blob override on top of the env
    // baseline; the new values only take effect after a container
    // restart because clients are constructed once at boot. The
    // response echoes back the merged-and-redacted settings the UI
    // will see at GET time.
    //
    // Field-level merge semantics: any field omitted from the body
    // (or sent as null) is treated as "no change" — the existing
    // override or baseline value is preserved. This lets the UI keep
    // secret fields blank to mean "don't touch".
    post("/settings") {
        val patch = try {
            call.receive<org.yoshiz.app.prioritarr.backend.config.EditableSettings>()
        } catch (e: Exception) {
            throw ValidationException("body", "invalid EditableSettings: ${e.message}")
        }
        // Merge with the existing override so partial updates accumulate.
        val existing = state.db.getSettingsOverride()?.let { raw ->
            try {
                Json.decodeFromString(
                    org.yoshiz.app.prioritarr.backend.config.EditableSettings.serializer(),
                    raw,
                )
            } catch (_: Exception) { null }
        } ?: org.yoshiz.app.prioritarr.backend.config.EditableSettings()
        val merged = mergeEditable(existing, patch)
        state.db.setSettingsOverride(
            Json.encodeToString(
                org.yoshiz.app.prioritarr.backend.config.EditableSettings.serializer(),
                merged,
            ),
        )
        state.eventBus.publish(
            "settings-updated",
            kotlinx.serialization.json.JsonNull,
        )
        // Echo back the *running* settings (which still reflect the
        // pre-restart values). The UI uses hasOverrides to surface
        // "restart to apply".
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
            traktClientId = s.traktClientId,
            traktAccessToken = redactSecret(s.traktAccessToken),
            apiKey = redactSecret(s.apiKey),
            uiOrigin = s.uiOrigin,
            dryRun = s.dryRun,
            logLevel = s.logLevel,
            testMode = s.testMode,
            hasOverrides = true,
        ))
    }

    delete("/settings") {
        state.db.clearSettingsOverride()
        state.eventBus.publish("settings-reset", kotlinx.serialization.json.JsonNull)
        call.respond(ActionResult(ok = true, message = "override cleared (restart to apply)"))
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
        val dryRun = call.request.queryParameters["dryRun"]?.equals("true", ignoreCase = true)
            ?: state.settings.dryRun
        // Optional cap on the number of series visited per call. The
        // sandbox preview button uses limit=20 so the dry-run completes
        // in seconds instead of minutes; a real (non-dry) sync is
        // intended to walk everything and so is unbounded by default.
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceAtLeast(1)
        val all = state.db.q.listSeriesCache().executeAsList()
        val targets = if (limit != null) all.take(limit) else all
        val perSeries = mutableListOf<SeriesSyncReport>()
        var plexTotal = 0
        var traktTotal = 0
        for (row in targets) {
            val r = state.crossSourceSync.syncSeries(row.id, dryRun = dryRun)
            perSeries += r
            plexTotal += r.plexAdded
            traktTotal += r.traktAdded
        }
        val agg = LibrarySyncReport(
            ok = perSeries.all { it.errors.isEmpty() && it.skippedReason == null },
            dryRun = dryRun,
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

    // ---- Orphan reaper ----
    //
    // POST /sweep — runs an OrphanReaper.sweep cycle synchronously
    // and returns the report. Honors ?dryRun=true override.
    // GET  /orphans — recent orphan_reaper_* audit entries (the
    // "delete/import/keep" journal — the UI's review surface).
    post("/orphans/sweep") {
        val dryRun = call.request.queryParameters["dryRun"]?.equals("true", ignoreCase = true)
            ?: state.settings.dryRun
        val report = state.orphanReaper.sweep(dryRun = dryRun)
        call.respond(report)
    }

    get("/orphans") {
        val limit = (call.request.queryParameters["limit"]?.toLongOrNull() ?: 500L).coerceIn(1, 5000)
        // SQL-side LIKE on action prefix — filtering in-memory on
        // listAuditFiltered would miss every reaper row whenever the
        // top-N audit rows are dominated by high-frequency events
        // like priority_set.
        val rows = state.db.q.listAuditByActionPrefix("orphan_reaper_%", limit).executeAsList()
        val typed = rows.map { r ->
            OrphanAuditRow(
                id = r.id,
                ts = r.ts,
                action = r.action,
                details = r.details?.let { Json.parseToJsonElement(it) },
            )
        }
        call.respond(typed)
    }

    // ---- Orphan per-file actions ----
    //
    // Bulk-delete: ignore ?dryRun, this is operator-initiated. Each
    // path goes through OrphanReaper.deleteOne which path-validates
    // against cleanupPaths so the UI can't be tricked into rm-ing
    // arbitrary files. Per-path outcome lets the UI render mixed
    // success/failure toasts.
    post("/orphans/delete") {
        val req = try { call.receive<OrphanPathsRequest>() }
        catch (e: Exception) { throw ValidationException("body", "must be {paths:[...]}: ${e.message}") }
        val outcomes = req.paths.map { p ->
            val path = java.nio.file.Paths.get(p)
            val ok = state.orphanReaper.deleteOne(path)
            OrphanPathOutcome(p, ok, if (ok) "deleted" else "rejected (outside cleanup paths or io error)")
        }
        call.respond(OrphanBulkResult(
            total = outcomes.size,
            succeeded = outcomes.count { it.ok },
            outcomes = outcomes,
        ))
    }

    // Rename in place. Use after seeing a "Unknown Series" / parse
    // failure to nudge Sonarr toward a match. Endpoint runs the
    // rename then returns a fresh probe so the UI can show the new
    // verdict without a second roundtrip.
    post("/orphans/rename") {
        val req = try { call.receive<OrphanRenameRequest>() }
        catch (e: Exception) { throw ValidationException("body", "must be {path, newName}: ${e.message}") }
        val from = java.nio.file.Paths.get(req.path)
        val to = state.orphanReaper.renameOne(from, req.newName)
        if (to == null) {
            call.respond(OrphanRenameResult(ok = false, message = "rename rejected — outside cleanup path, illegal name, or io error"))
            return@post
        }
        call.respond(OrphanRenameResult(ok = true, newPath = to.toString()))
    }

    // Re-probe a single path against Sonarr — runs after rename or
    // for any stale "kept" entry whose situation may have changed.
    post("/orphans/probe") {
        val req = try { call.receive<OrphanRenameRequest>() }  // reuses path field
        catch (e: Exception) { throw ValidationException("body", "must be {path}: ${e.message}") }
        val path = java.nio.file.Paths.get(req.path)
        val probe = state.orphanReaper.probeOne(path)
        if (probe == null || probe.size == 0) {
            call.respond(OrphanProbeResult(
                ok = true, canImport = false,
                rejections = listOf("Sonarr couldn't parse this file"),
            ))
            return@post
        }
        val item = probe[0].jsonObject
        val rej = (item["rejections"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as JsonObject)["reason"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?: emptyList()
        val seriesTitle = (item["series"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
        val episodes = (item["episodes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { e ->
                val o = (e as? JsonObject) ?: return@mapNotNull null
                val s = o["seasonNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                val n = o["episodeNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                "S%02dE%02d".format(s, n)
            } ?: emptyList()
        call.respond(OrphanProbeResult(
            ok = true,
            canImport = rej.isEmpty(),
            rejections = rej,
            seriesTitle = seriesTitle,
            episodes = episodes,
        ))
    }

    // Trigger a Sonarr ManualImport for a single orphan that the UI
    // has confirmed (via /orphans/probe) as importable. Used as the
    // "Import" button on the review row; convenience over running a
    // whole sweep.
    post("/orphans/import") {
        val req = try { call.receive<OrphanRenameRequest>() }
        catch (e: Exception) { throw ValidationException("body", "must be {path}: ${e.message}") }
        val path = java.nio.file.Paths.get(req.path)
        val ok = state.orphanReaper.importOne(path)
        call.respond(OrphanPathOutcome(path = req.path, ok = ok,
            message = if (ok) "Sonarr ManualImport queued" else "import failed (probe rejected or io error)"))
    }

    // ---- Search ----
    //
    // Matches series by title OR by any of their monitored episode
    // titles (case-insensitive substring). Backed by the episode_cache
    // read-model so a query is a single SQL scan, not a fan-out to
    // Sonarr. Returns each series at most once; when the match was on
    // an episode, the first matching (season, number, title) is echoed
    // back for UI context.
    get("/search") {
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        val limit = (call.request.queryParameters["limit"]?.toLongOrNull() ?: 50L).coerceIn(1, 200)
        if (q.length < 2) {
            // Below 2 chars the result set would be too large to be useful
            // (and expensive). Return an empty list rather than 400 so the
            // frontend can render "keep typing" UX without an error path.
            call.respond(SearchResponse(query = q, hits = emptyList()))
            return@get
        }
        val like = "%${q.lowercase()}%"
        val rows = state.db.q.searchSeriesByTitleOrEpisode(like, limit).executeAsList()
        // First-match-per-series — the SQL returned every matching
        // (series, episode) row; collapse to the first hit keeping the
        // "title" match winner if present.
        val bySeries = linkedMapOf<Long, SearchHit>()
        for (row in rows) {
            val existing = bySeries[row.series_id]
            val matchedBy = row.matched_by
            val hit = SearchHit(
                seriesId = row.series_id,
                title = row.series_title,
                matchedBy = matchedBy,
                matchedEpisode = if (matchedBy == "episode" && row.season_number != null && row.episode_number != null && row.episode_title != null) {
                    MatchedEpisode(
                        season = row.season_number.toInt(),
                        number = row.episode_number.toInt(),
                        title = row.episode_title,
                    )
                } else null,
            )
            // Prefer title matches over episode matches when both present.
            if (existing == null || (existing.matchedBy == "episode" && matchedBy == "title")) {
                bySeries[row.series_id] = hit
            }
        }
        call.respond(SearchResponse(query = q, hits = bySeries.values.toList()))
    }

    // ---- Priority thresholds (live-editable) ----
    //
    // GET returns the effective thresholds (baseline ∘ overrides); PUT
    // replaces the override blob entirely with the posted object and
    // invalidates the whole priority cache so the next refresh cycle
    // picks up the new rules. No partial patch on PUT — the UI sends
    // the full object; callers that want to reset can use DELETE.
    get("/settings/thresholds") {
        call.respond(state.thresholdsSource.current())
    }

    post("/settings/thresholds") {
        val next = call.receive<org.yoshiz.app.prioritarr.backend.config.PriorityThresholds>()
        state.thresholdsSource.save(next)
        // Drop the entire priority cache so every series recomputes.
        state.db.q.deleteAllSeriesPriorityCache()
        state.eventBus.publish(
            "thresholds-updated",
            Json.encodeToJsonElement(
                org.yoshiz.app.prioritarr.backend.config.PriorityThresholds.serializer(),
                next,
            ),
        )
        call.respond(state.thresholdsSource.current())
    }

    delete("/settings/thresholds") {
        state.thresholdsSource.reset()
        state.db.q.deleteAllSeriesPriorityCache()
        state.eventBus.publish(
            "thresholds-reset",
            kotlinx.serialization.json.JsonNull,
        )
        call.respond(state.thresholdsSource.current())
    }

    // ---- What-if preview ----
    //
    // Runs the full compute for up to 3 series using a patched
    // thresholds object, without writing anything. The response
    // carries the decision inputs (pct, unwatched, days) so the UI
    // can show a diff against the currently-cached priority.
    post("/priority/preview") {
        val req = try {
            call.receive<PriorityPreviewRequest>()
        } catch (e: Exception) {
            throw ValidationException("body", "must be {seriesIds:[...], thresholds:{...}}: ${e.message}")
        }
        if (req.seriesIds.isEmpty()) {
            throw ValidationException("seriesIds", "must contain at least one id")
        }
        if (req.seriesIds.size > 10) {
            throw ValidationException("seriesIds", "maximum 10 series per preview")
        }
        val base = state.thresholdsSource.current()
        val patched = org.yoshiz.app.prioritarr.backend.priority.applyPatch(base, req.thresholds)

        val entries = req.seriesIds.map { sid ->
            val preview = state.priorityService.preview(sid, patched)
            val cached = state.db.getPriorityCache(sid)
            val title = state.db.q.selectSeriesCache(sid).executeAsOneOrNull()?.title ?: "(unknown)"
            val managed = state.db.listManagedDownloads().filter { it.series_id == sid }
            if (preview == null) {
                PriorityPreviewEntry(
                    seriesId = sid,
                    title = title,
                    monitoredSeasons = 0,
                    monitoredEpisodesAired = 0,
                    monitoredEpisodesWatched = 0,
                    unwatched = 0,
                    watchPct = 0.0,
                    daysSinceWatch = null,
                    daysSinceRelease = null,
                    previous = cached?.let { PriorityResultWire(it.priority.toInt(), "P${it.priority}", it.reason.orEmpty()) },
                    preview = PriorityResultWire(5, "P5", "snapshot unavailable"),
                    downloads = emptyList(),
                )
            } else {
                val snap = preview.snapshot
                val aired = snap.monitoredEpisodesAired
                val watched = snap.monitoredEpisodesWatched
                val unwatched = aired - watched
                val now = java.time.Instant.now()
                val dsw = snap.lastWatchedAt?.let { java.time.Duration.between(it, now).toDays().toInt() }
                val dsr = snap.episodeReleaseDate?.let { java.time.Duration.between(it, now).toDays().toInt() }

                // Overlay preview priority on each managed download so
                // we can re-run the pause-band calc and report "would
                // still be paused" per download.
                val overlayedView = managed.map { row ->
                    val overlay = if (row.series_id == sid) preview.result.priority else row.current_priority.toInt()
                    org.yoshiz.app.prioritarr.backend.enforcement.QBitDownloadView(
                        hash = row.client_id,
                        priority = overlay,
                        state = if (row.paused_by_us == 1L) "pausedDL" else "downloading",
                        pausedByUs = row.paused_by_us == 1L,
                    )
                }
                val actions = org.yoshiz.app.prioritarr.backend.enforcement
                    .computeQBitPauseActions(overlayedView)
                val pausedHashes = actions.filter { it.action == "pause" }.map { it.hash }.toSet()

                PriorityPreviewEntry(
                    seriesId = sid,
                    title = title,
                    monitoredSeasons = snap.monitoredSeasons,
                    monitoredEpisodesAired = aired,
                    monitoredEpisodesWatched = watched,
                    unwatched = unwatched,
                    watchPct = if (aired > 0) watched.toDouble() / aired.toDouble() else 0.0,
                    daysSinceWatch = dsw,
                    daysSinceRelease = dsr,
                    previous = cached?.let { PriorityResultWire(it.priority.toInt(), "P${it.priority}", it.reason.orEmpty()) },
                    preview = PriorityResultWire(
                        preview.result.priority,
                        preview.result.label,
                        preview.result.reason,
                    ),
                    downloads = managed.map { row ->
                        ManagedDownloadPreview(
                            client = row.client,
                            clientId = row.client_id,
                            currentPriority = row.current_priority.toInt(),
                            currentlyPausedByUs = row.paused_by_us == 1L,
                            wouldBePaused = row.client_id in pausedHashes,
                        )
                    },
                )
            }
        }
        call.respond(PriorityPreviewResponse(thresholds = patched, entries = entries))
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

    val dc = state.downloadClients[client]
        ?: return BulkItemResult(client, clientId, ok = false, message = "unknown client: $client")
    try {
        when (action) {
            "pause" -> dc.pauseOne(clientId)
            "resume" -> dc.resumeOne(clientId)
            "boost" -> dc.boostOne(clientId)
            "demote" -> dc.demoteOne(clientId)
            else -> return BulkItemResult(client, clientId, ok = false, message = "unknown action: $action")
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
