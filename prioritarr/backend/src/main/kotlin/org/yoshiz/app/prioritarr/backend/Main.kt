package org.yoshiz.app.prioritarr.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.yoshiz.app.prioritarr.backend.app.AppState
import org.yoshiz.app.prioritarr.backend.app.prioritarrModule
import org.yoshiz.app.prioritarr.backend.events.EventBus
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.yoshiz.app.prioritarr.backend.config.loadSettingsFromEnv
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.http.defaultJsonClient
import org.yoshiz.app.prioritarr.backend.http.qbitClient
import org.yoshiz.app.prioritarr.backend.http.xmlClient
import org.yoshiz.app.prioritarr.backend.mapping.Hydrate
import org.yoshiz.app.prioritarr.backend.mapping.MappingCache
import org.yoshiz.app.prioritarr.backend.mapping.SqliteMappingCache
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import org.yoshiz.app.prioritarr.backend.mapping.hydrate
import org.yoshiz.app.prioritarr.backend.priority.PriorityService
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.Main")

/**
 * Read the latest editable settings override from DB, fall back to an
 * empty patch if missing or unparseable. Used by the Trakt token
 * refresh path so it sees the freshest refresh_token even when the
 * boot-time [Settings] snapshot is stale.
 */
internal fun readEditableOverride(
    db: org.yoshiz.app.prioritarr.backend.database.Database,
): org.yoshiz.app.prioritarr.backend.config.EditableSettings =
    db.getSettingsOverride()?.let { raw ->
        try {
            kotlinx.serialization.json.Json.decodeFromString(
                org.yoshiz.app.prioritarr.backend.config.EditableSettings.serializer(),
                raw,
            )
        } catch (_: Exception) { null }
    } ?: org.yoshiz.app.prioritarr.backend.config.EditableSettings()

/**
 * Snapshot the *currently effective* settings — boot-time baseline
 * with the latest DB override applied. Schedulers call this each tick
 * so cadence + feature-flag changes propagate without restart.
 *
 * Cheap: one DB read + a data-class copy. The DB query has its own
 * SQLDelight prepared statement; we'd need to be deep into the
 * thousands-of-ticks/sec range before it mattered.
 */
internal fun liveSettings(
    db: org.yoshiz.app.prioritarr.backend.database.Database,
    baseline: org.yoshiz.app.prioritarr.backend.config.Settings,
): org.yoshiz.app.prioritarr.backend.config.Settings =
    org.yoshiz.app.prioritarr.backend.config.applySettingsOverride(baseline, readEditableOverride(db))

// `JobOutcome` and the per-tick `trackJob` wrapper used to live here.
// They're now provided by the [scheduler.Scheduler], which calls
// `db.recordJobRun(...)` directly after each launched job finishes.

/**
 * Mint a fresh Trakt access_token from the stored refresh_token,
 * persist the new tokens, and return the new access_token. Returns
 * null when no refresh_token is available or Trakt rejected the
 * refresh — caller should propagate the original auth failure.
 *
 * Persistence happens via the standard settings-override path so a
 * subsequent container restart picks up the same tokens. The running
 * TraktClient also gets a hot-swap via [TraktClient.updateAccessToken]
 * (the on-401 callback variant assigns directly inside the client).
 */
/**
 * Wipe stored Trakt tokens (access + refresh + expiry). Used when
 * Trakt rejects the refresh_token as invalid/expired — the resulting
 * "no tokens" state is identical to first-time setup, so the UI's
 * "Connect Trakt" affordance reappears automatically and the user
 * runs through the device-code flow again. Keeps client_id/secret.
 */
private fun wipeTraktTokens(db: org.yoshiz.app.prioritarr.backend.database.Database) {
    val current = readEditableOverride(db)
    val cleared = current.copy(
        traktAccessToken = "",
        traktRefreshToken = "",
        traktTokenExpiresAt = "",
    )
    db.setSettingsOverride(
        kotlinx.serialization.json.Json.encodeToString(
            org.yoshiz.app.prioritarr.backend.config.EditableSettings.serializer(),
            cleared,
        ),
    )
}

internal suspend fun refreshTraktTokensFromDb(
    db: org.yoshiz.app.prioritarr.backend.database.Database,
    baseline: org.yoshiz.app.prioritarr.backend.config.Settings,
    oauth: org.yoshiz.app.prioritarr.backend.clients.TraktOAuth,
): String? {
    val current = readEditableOverride(db)
    val refreshToken = current.traktRefreshToken?.takeIf { it.isNotBlank() }
        ?: baseline.traktRefreshToken?.takeIf { it.isNotBlank() }
    if (refreshToken == null) {
        logger.warn("trakt-refresh: no refresh_token stored — cannot auto-refresh")
        return null
    }
    val tokens = try {
        oauth.refresh(refreshToken)
    } catch (e: io.ktor.client.plugins.ClientRequestException) {
        // 4xx from /oauth/token = refresh_token is invalid or revoked
        // (typical after >90 days idle, manual revoke at trakt.tv, or
        // app credentials rotated). Wipe so the UI flips back to the
        // "Connect Trakt" affordance — same state as a fresh user.
        logger.warn("trakt-refresh: refresh_token rejected ({}); wiping stored tokens", e.response.status)
        wipeTraktTokens(db)
        return null
    } catch (e: Exception) {
        // 5xx or network error — keep tokens, retry next tick.
        logger.warn("trakt-refresh: transient failure, will retry: {}", e.message)
        return null
    }
    val newAccess = (tokens["access_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    if (newAccess.isNullOrBlank()) {
        logger.warn("trakt-refresh: response missing access_token")
        return null
    }
    val newRefresh = (tokens["refresh_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: refreshToken
    val expiresIn = (tokens["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    val createdAt = (tokens["created_at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
    val expiresAt = (createdAt?.let { Instant.ofEpochSecond(it) } ?: Instant.now())
        .plusSeconds(expiresIn)
        .toString()
    val merged = current.copy(
        traktAccessToken = newAccess,
        traktRefreshToken = newRefresh,
        traktTokenExpiresAt = expiresAt,
    )
    db.setSettingsOverride(
        kotlinx.serialization.json.Json.encodeToString(
            org.yoshiz.app.prioritarr.backend.config.EditableSettings.serializer(),
            merged,
        ),
    )
    logger.info("trakt-refresh: token refreshed; new expiry={}", expiresAt)
    return newAccess
}

fun main() {
    val baseSettings = loadSettingsFromEnv()
    val db = Database("/config/state.db")

    // Apply persisted DB overrides on top of the env-loaded baseline.
    // Edits made via the Settings UI persist here and only take effect
    // at boot — clients are constructed once and held in AppState.
    val settings = run {
        val raw = db.getSettingsOverride() ?: return@run baseSettings
        try {
            val patch = kotlinx.serialization.json.Json.decodeFromString(
                org.yoshiz.app.prioritarr.backend.config.EditableSettings.serializer(),
                raw,
            )
            org.yoshiz.app.prioritarr.backend.config.applySettingsOverride(baseSettings, patch)
        } catch (e: Exception) {
            logger.warn("settings override unparseable: {}; using env baseline", e.message)
            baseSettings
        }
    }
    logger.info("prioritarr (kotlin) starting (dry_run={}, test_mode={})", settings.dryRun, settings.testMode)

    val sonarrHttp = defaultJsonClient()
    val tautulliHttp = defaultJsonClient()
    val plexHttp = xmlClient()
    val qbitHttp = qbitClient()
    val sabHttp = defaultJsonClient()
    val traktHttp = defaultJsonClient()
    // Tiny dedicated HTTP client for health probes — short timeouts so a
    // hung upstream can't stall a scheduler tick the way a 120s default
    // probe would. Closing handled via AppState.httpClients ownership.
    val healthHttp = defaultJsonClient(timeoutMs = 10_000)

    val sonarr = SonarrClient(settings.sonarrUrl, settings.sonarrApiKey, sonarrHttp)
    val tautulli = TautulliClient(settings.tautulliUrl, settings.tautulliApiKey, tautulliHttp)
    val qbit = QBitClient(settings.qbitUrl, settings.qbitUsername.orEmpty(), settings.qbitPassword.orEmpty(), qbitHttp)
    val sab = SABClient(settings.sabUrl, settings.sabApiKey, sabHttp)
    // Plex direct client — only built when both URL + token configured.
    // Used by both PlexHistoryProvider (read) and CrossSourceSync (write).
    val plexClient: org.yoshiz.app.prioritarr.backend.clients.PlexClient? =
        if (!settings.plexUrl.isNullOrBlank() && !settings.plexToken.isNullOrBlank()) {
            org.yoshiz.app.prioritarr.backend.clients.PlexClient(settings.plexUrl, settings.plexToken, plexHttp)
        } else null

    val mappings = MappingState()

    val cache: MappingCache = SqliteMappingCache(db)
    // Re-hydrate the plex-key map at startup so webhooks work before the
    // first refresh. TVDB + title maps fill in on the next refresh cycle.
    mappings.hydrate(Hydrate.seed(cache.load()))

    // Assemble whichever watch-history providers are configured. Tautulli
    // is always included (the app won't start without its URL + key);
    // Plex + Trakt join if their respective env vars are set. The Trakt
    // client is also stored separately so cross-source sync can reach it.
    val tautulliProvider = org.yoshiz.app.prioritarr.backend.priority.TautulliHistoryProvider(tautulli, mappings)
    val plexProvider: org.yoshiz.app.prioritarr.backend.priority.WatchHistoryProvider? =
        if (plexClient != null) {
            logger.info("plex: direct watch-history provider enabled")
            org.yoshiz.app.prioritarr.backend.priority.PlexHistoryProvider(plexClient, mappings)
        } else {
            logger.info("plex: direct provider disabled (URL/token not configured)")
            null
        }
    // OAuth helper available whenever client_id + client_secret are
    // both present. Used by the on-401 reactive refresh and the daily
    // proactive refresh job.
    val traktOAuth: org.yoshiz.app.prioritarr.backend.clients.TraktOAuth? =
        if (!settings.traktClientId.isNullOrBlank() && !settings.traktClientSecret.isNullOrBlank()) {
            org.yoshiz.app.prioritarr.backend.clients.TraktOAuth(
                clientId = settings.traktClientId,
                clientSecret = settings.traktClientSecret,
                http = traktHttp,
            )
        } else null

    val traktClient: org.yoshiz.app.prioritarr.backend.clients.TraktClient? =
        if (settings.traktClientId != null && settings.traktAccessToken != null) {
            org.yoshiz.app.prioritarr.backend.clients.TraktClient(
                clientId = settings.traktClientId,
                initialAccessToken = settings.traktAccessToken,
                http = traktHttp,
                onRefreshNeeded = if (traktOAuth != null) {
                    // Reactive refresh: runs on the FIRST 401, persists
                    // the new tokens, returns the new access_token so
                    // the failing request can be retried in-flight.
                    suspend { refreshTraktTokensFromDb(db, settings, traktOAuth) }
                } else null,
            )
        } else null
    val traktProvider: org.yoshiz.app.prioritarr.backend.priority.WatchHistoryProvider? =
        if (traktClient != null) {
            logger.info("trakt: watch-history provider enabled")
            org.yoshiz.app.prioritarr.backend.priority.TraktHistoryProvider(traktClient)
        } else {
            logger.info("trakt: not configured, using other providers only")
            null
        }
    val watchProviders = listOfNotNull(tautulliProvider, plexProvider, traktProvider)
    val crossSourceSync = org.yoshiz.app.prioritarr.backend.sync.CrossSourceSync(
        sonarr = sonarr,
        plex = plexClient,
        trakt = traktClient,
        mappings = mappings,
    )

    val thresholdsSource = org.yoshiz.app.prioritarr.backend.priority.DbThresholdsSource(
        db = db,
        baseline = settings.priorityThresholds,
    )
    val priorityService = PriorityService(
        sonarr = sonarr,
        watchProviders = watchProviders,
        db = db,
        thresholdsSource = thresholdsSource,
        cacheTtlMinutes = settings.cache.priorityTtlMinutes.toLong(),
    )

    val orphanReaper = org.yoshiz.app.prioritarr.backend.reconcile.OrphanReaper(
        qbit = qbit, sab = sab, sonarr = sonarr, db = db,
        cleanupPaths = { liveSettings(db, settings).orphanReaperPaths },
        autoImport = true,
    )
    val watchedArchiver = org.yoshiz.app.prioritarr.backend.reconcile.WatchedArchiver(
        sonarr = sonarr,
        watchProviders = watchProviders,
        db = db,
    )
    val traktUnmonitor = org.yoshiz.app.prioritarr.backend.reconcile.TraktUnmonitorReconciler(
        sonarr = sonarr,
        trakt = traktClient,
        db = db,
    )

    val state = AppState(
        settings = settings,
        db = db,
        sonarr = sonarr,
        tautulli = tautulli,
        qbit = qbit,
        sab = sab,
        downloadClients = mapOf(qbit.clientName to qbit, sab.clientName to sab),
        downloadTelemetry = org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry(),
        bandwidthSource = org.yoshiz.app.prioritarr.backend.enforcement.DbBandwidthSource(
            db = db, baseline = settings.bandwidth,
        ),
        mappings = mappings,
        priorityService = priorityService,
        thresholdsSource = thresholdsSource,
        crossSourceSync = crossSourceSync,
        orphanReaper = orphanReaper,
        watchedArchiver = watchedArchiver,
        traktUnmonitor = traktUnmonitor,
        traktClient = traktClient,
        traktOAuth = traktOAuth,
        eventBus = EventBus(),
        httpClients = listOf(sonarrHttp, tautulliHttp, plexHttp, qbitHttp, sabHttp, traktHttp, healthHttp),
    )

    // Heartbeat coroutine — enough to make /health flip to 200 after startup.
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    scope.launch {
        while (isActive) {
            try {
                db.updateHeartbeat()
            } catch (e: Exception) {
                logger.warn("heartbeat write failed: {}", e.message)
            }
            delay(30_000)
        }
    }

    // SSE heartbeat event publisher (distinct from the db heartbeat above).
    org.yoshiz.app.prioritarr.backend.api.v2.startHeartbeat(state, scope.coroutineContext[Job]!!)

    // Background jobs — declarative registry driving a single tick
    // loop in [Scheduler]. Each [JobDefinition]'s prerequisites +
    // cadence are read live (same liveSettings pattern as before),
    // so toggling a job mid-session takes effect on the next tick
    // without a container restart. Heavy jobs are capped at 1 per
    // tick to prevent dogpiling Sonarr.
    val queueJanitor = org.yoshiz.app.prioritarr.backend.reconcile.QueueJanitor(
        sonarr = sonarr, qbit = qbit, sab = sab, db = db,
    )
    val unmonitoredReaper = org.yoshiz.app.prioritarr.backend.reconcile.UnmonitoredReaper(
        sonarr = sonarr, db = db,
    )

    val healthMonitor = org.yoshiz.app.prioritarr.backend.health.HealthMonitor(
        db = db,
        settings = settings,
        http = healthHttp,
    )

    val scheduler = org.yoshiz.app.prioritarr.backend.scheduler.Scheduler(
        db = db,
        jobs = buildList {
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.REFRESH_MAPPINGS,
                cadenceMinutes = { liveSettings(db, settings).intervals.refreshMappingsMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                run = {
                    org.yoshiz.app.prioritarr.backend.mapping.refreshMappings(sonarr, tautulli, cache, mappings)
                    state.eventBus.publish("mapping-refreshed", kotlinx.serialization.json.JsonNull)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.REFRESH_SERIES_CACHE,
                cadenceMinutes = { liveSettings(db, settings).intervals.refreshSeriesCacheMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                run = {
                    org.yoshiz.app.prioritarr.backend.series.refreshSeriesCache(sonarr, db)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.REFRESH_EPISODE_CACHE,
                cadenceMinutes = { liveSettings(db, settings).intervals.refreshEpisodeCacheMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                firstRunDelayMinutes = 1,  // wait for series_cache to seed
                run = {
                    org.yoshiz.app.prioritarr.backend.series.refreshEpisodeCache(sonarr, db)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.RECONCILE,
                cadenceMinutes = { liveSettings(db, settings).intervals.reconcileMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                run = {
                    val s = liveSettings(db, settings)
                    val lookup = org.yoshiz.app.prioritarr.backend.reconcile.fetchSonarrQueueLookup(sonarr)
                    org.yoshiz.app.prioritarr.backend.reconcile.reconcileQbit(
                        qbit, db, lookup, priorityService, s.dryRun,
                        bandwidth = state.bandwidthSource.current(),
                        telemetry = state.downloadTelemetry,
                    )
                    org.yoshiz.app.prioritarr.backend.reconcile.reconcileSab(sab, db, lookup, priorityService, s.dryRun)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.REFRESH_PRIORITIES,
                cadenceMinutes = { liveSettings(db, settings).intervals.refreshPrioritiesMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                run = {
                    org.yoshiz.app.prioritarr.backend.priority.refreshAllPriorities(sonarr, priorityService)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.QUEUE_JANITOR,
                cadenceMinutes = { liveSettings(db, settings).intervals.queueJanitorMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                run = {
                    queueJanitor.sweep(dryRun = liveSettings(db, settings).dryRun)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.UNMONITORED_REAPER,
                cadenceMinutes = { liveSettings(db, settings).intervals.unmonitoredReaperMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                firstRunDelayMinutes = 2,
                run = {
                    unmonitoredReaper.sweep(dryRun = liveSettings(db, settings).dryRun)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.TRAKT_UNMONITOR,
                cadenceMinutes = { liveSettings(db, settings).traktUnmonitor.intervalHours.toLong() * 60L },
                // Reactive prereq: enabling the toggle flips this true,
                // and the next tick (≤60s later) runs the reconciler —
                // no restart needed.
                prerequisites = { liveSettings(db, settings).traktUnmonitor.enabled },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                firstRunDelayMinutes = 15,
                run = {
                    val s = liveSettings(db, settings)
                    val report = traktUnmonitor.reconcileAll(s.traktUnmonitor, dryRun = s.dryRun)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(
                        summary = "${report.totalSeries} series, ${report.unmonitoredTotal} unmonitored",
                    )
                },
            ))
            if (traktOAuth != null && traktClient != null) {
                add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                    id = JobId.TRAKT_TOKEN_REFRESH,
                    cadenceMinutes = { liveSettings(db, settings).intervals.traktTokenRefreshHours.toLong() * 60L },
                    weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                    firstRunDelayMinutes = 30,
                    run = {
                        val current = readEditableOverride(db)
                        val expiresIso = current.traktTokenExpiresAt?.takeIf { it.isNotBlank() }
                            ?: settings.traktTokenExpiresAt?.takeIf { it.isNotBlank() }
                        if (expiresIso == null) {
                            org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(summary = "no expiry set", noop = true)
                        } else {
                            val expires = OffsetDateTime.parse(expiresIso).toInstant()
                            val daysLeft = Duration.between(Instant.now(), expires).toDays()
                            if (daysLeft > 7) {
                                org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(summary = "$daysLeft days left, no refresh needed", noop = true)
                            } else {
                                logger.info("trakt-refresh: token expires in {} days; proactively refreshing", daysLeft)
                                val newToken = refreshTraktTokensFromDb(db, settings, traktOAuth)
                                if (newToken != null) {
                                    traktClient.updateAccessToken(newToken)
                                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(summary = "refreshed (was $daysLeft days from expiry)")
                                } else {
                                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(summary = "refresh failed; tokens cleared, awaits reconnect")
                                }
                            }
                        }
                    },
                ))
            }
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.WATCHED_ARCHIVER,
                cadenceMinutes = { liveSettings(db, settings).archive.intervalHours.toLong() * 60L },
                prerequisites = { liveSettings(db, settings).archive.watchedEnabled },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                firstRunDelayMinutes = 10,
                run = {
                    val s = liveSettings(db, settings)
                    val report = watchedArchiver.sweep(s.archive, dryRun = s.dryRun)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(
                        summary = "${report.seriesVisited} series, ${report.deleted} deleted",
                    )
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.ORPHAN_REAPER,
                cadenceMinutes = { liveSettings(db, settings).orphanReaperIntervalMinutes.toLong() },
                // Skip if the path list is empty — operator's way of
                // disabling the reaper without disabling the cadence.
                prerequisites = { liveSettings(db, settings).orphanReaperPaths.isNotEmpty() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                firstRunDelayMinutes = 5,
                run = {
                    orphanReaper.sweep(dryRun = liveSettings(db, settings).dryRun)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.BACKFILL_SWEEP,
                cadenceMinutes = { liveSettings(db, settings).intervals.backfillSweepHours.toLong() * 60L },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                run = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.sweep.runBackfillSweep(
                        sonarr, priorityService,
                        maxSearches = s.intervals.backfillMaxSearchesPerSweep,
                        delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                        dryRun = s.dryRun,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.CUTOFF_SWEEP,
                cadenceMinutes = { liveSettings(db, settings).intervals.cutoffSweepHours.toLong() * 60L },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                run = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.sweep.runCutoffSweep(
                        sonarr, priorityService,
                        maxSearches = s.intervals.cutoffMaxSearchesPerSweep,
                        delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                        dryRun = s.dryRun,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.HEALTH_MONITOR,
                // Cadence intentionally hard-coded for now — surfacing it
                // as a setting buys little (5 min is a fine default and
                // changing it doesn't change correctness) and adds a UI
                // knob users would mostly leave alone.
                cadenceMinutes = { 5L },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                // Run once a few seconds after boot so the dashboard
                // banner doesn't sit empty on first paint.
                firstRunDelayMinutes = 0,
                run = {
                    val unhealthy = healthMonitor.probeAll()
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(
                        summary = if (unhealthy == 0) "all healthy" else "$unhealthy unhealthy",
                    )
                },
            ))
        },
    )
    scheduler.start(scope)

    embeddedServer(Netty, port = 8000, host = "0.0.0.0") {
        prioritarrModule(state)
    }.start(wait = true)
}
