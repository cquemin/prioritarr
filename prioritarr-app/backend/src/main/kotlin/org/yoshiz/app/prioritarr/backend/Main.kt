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

/**
 * Outcome of a scheduler tick. `summary` lands in the job_runs table
 * for display in the UI; null = no special summary, just status='ok'.
 * `noop` means the tick was scheduled but did nothing intentionally
 * (e.g. trakt_unmonitor when disabled). 'noop' is recorded so the UI
 * shows "ran but skipped" rather than "never ran".
 */
internal data class JobOutcome(val summary: String? = null, val noop: Boolean = false)

/**
 * Wrap one scheduler iteration: time it, persist the outcome, swallow
 * exceptions so a single failure doesn't kill the supervised coroutine.
 * Existing logger.warn semantics are preserved — same message, same
 * level — so existing log greps keep working.
 */
private suspend inline fun trackJob(
    db: org.yoshiz.app.prioritarr.backend.database.Database,
    jobId: String,
    crossinline block: suspend () -> JobOutcome,
) {
    val started = java.time.Instant.now()
    try {
        val out = block()
        db.recordJobRun(
            jobId = jobId,
            startedAt = started,
            finishedAt = java.time.Instant.now(),
            status = if (out.noop) "noop" else "ok",
            summary = out.summary,
        )
    } catch (e: Exception) {
        // Truncate long stack-trace strings — SQLite handles big
        // payloads, but the UI surfaces this verbatim and a 50KB
        // stacktrace makes the page unusable.
        val msg = (e.message ?: e::class.simpleName ?: "unknown").take(2000)
        db.recordJobRun(
            jobId = jobId,
            startedAt = started,
            finishedAt = java.time.Instant.now(),
            status = "error",
            errorMessage = msg,
        )
        logger.warn("$jobId: ${e.message}")
    }
}

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
        cleanupPaths = settings.orphanReaperPaths,
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
        httpClients = listOf(sonarrHttp, tautulliHttp, plexHttp, qbitHttp, sabHttp, traktHttp),
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

    // Background jobs. Each runs in its own coroutine under the
    // supervisor so one crashing doesn't take the others down. Every
    // tick re-reads `liveSettings(db, settings)` so cadence + flag
    // changes propagate within at most one full interval — no restart.
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_mappings crashed", e) }) {
        while (isActive) {
            trackJob(db, "refresh-mappings") {
                org.yoshiz.app.prioritarr.backend.mapping.refreshMappings(sonarr, tautulli, cache, mappings)
                state.eventBus.publish("mapping-refreshed", kotlinx.serialization.json.JsonNull)
                JobOutcome()
            }
            delay(liveSettings(db, settings).intervals.refreshMappingsMinutes.toLong() * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_series_cache crashed", e) }) {
        while (isActive) {
            trackJob(db, "refresh-series-cache") {
                org.yoshiz.app.prioritarr.backend.series.refreshSeriesCache(sonarr, db)
                JobOutcome()
            }
            delay(liveSettings(db, settings).intervals.refreshSeriesCacheMinutes.toLong() * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_episode_cache crashed", e) }) {
        delay(30_000)
        while (isActive) {
            trackJob(db, "refresh-episode-cache") {
                org.yoshiz.app.prioritarr.backend.series.refreshEpisodeCache(sonarr, db)
                JobOutcome()
            }
            delay(liveSettings(db, settings).intervals.refreshEpisodeCacheMinutes.toLong() * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("reconcile crashed", e) }) {
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "reconcile") {
                val lookup = org.yoshiz.app.prioritarr.backend.reconcile.fetchSonarrQueueLookup(sonarr)
                org.yoshiz.app.prioritarr.backend.reconcile.reconcileQbit(
                    qbit, db, lookup, priorityService, s.dryRun,
                    bandwidth = state.bandwidthSource.current(),
                    telemetry = state.downloadTelemetry,
                )
                org.yoshiz.app.prioritarr.backend.reconcile.reconcileSab(sab, db, lookup, priorityService, s.dryRun)
                JobOutcome()
            }
            delay(s.intervals.reconcileMinutes.toLong() * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_priorities crashed", e) }) {
        while (isActive) {
            trackJob(db, "refresh-priorities") {
                org.yoshiz.app.prioritarr.backend.priority.refreshAllPriorities(sonarr, priorityService)
                JobOutcome()
            }
            delay(liveSettings(db, settings).intervals.refreshPrioritiesMinutes.toLong() * 60L * 1000L)
        }
    }
    val queueJanitor = org.yoshiz.app.prioritarr.backend.reconcile.QueueJanitor(
        sonarr = sonarr, qbit = qbit, sab = sab, db = db,
    )
    val unmonitoredReaper = org.yoshiz.app.prioritarr.backend.reconcile.UnmonitoredReaper(
        sonarr = sonarr, db = db,
    )
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("queue_janitor crashed", e) }) {
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "queue-janitor") {
                queueJanitor.sweep(dryRun = s.dryRun)
                JobOutcome()
            }
            delay(s.intervals.queueJanitorMinutes.toLong() * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("unmonitored_reaper crashed", e) }) {
        delay(2L * 60L * 1000L)
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "unmonitored-reaper") {
                unmonitoredReaper.sweep(dryRun = s.dryRun)
                JobOutcome()
            }
            delay(s.intervals.unmonitoredReaperMinutes.toLong() * 60L * 1000L)
        }
    }

    // Trakt→Sonarr unmonitor reconciler — disabled by default; when
    // on, every intervalHours it walks the library and unmonitors
    // Sonarr episodes that Trakt says you've already watched but
    // that aren't on disk. Protect-tag honoured inside the reconciler.
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("trakt_unmonitor crashed", e) }) {
        // Stagger 15 min after boot so it doesn't race with the initial
        // CrossSourceSync / series_cache warmup.
        delay(15L * 60L * 1000L)
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "trakt-unmonitor") {
                if (!s.traktUnmonitor.enabled) {
                    JobOutcome(summary = "scheduler disabled", noop = true)
                } else {
                    val report = traktUnmonitor.reconcileAll(s.traktUnmonitor, dryRun = s.dryRun)
                    JobOutcome(summary = "${report.totalSeries} series, ${report.unmonitoredTotal} unmonitored")
                }
            }
            delay(s.traktUnmonitor.intervalHours.toLong() * 60L * 60L * 1000L)
        }
    }

    // Proactive Trakt token refresh — daily check; if the access token
    // expires within 7 days we mint a fresh one ahead of the deadline.
    // The reactive on-401 path inside TraktClient covers the case where
    // the container was off long enough that the proactive job missed
    // a window. Both feed the same `refreshTraktTokensFromDb` helper.
    if (traktOAuth != null && traktClient != null) {
        scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("trakt_token_refresh crashed", e) }) {
            // 30 min stagger after boot — gives the cross-source sync
            // and series cache time to warm before we touch tokens.
            delay(30L * 60L * 1000L)
            while (isActive) {
                trackJob(db, "trakt-token-refresh") {
                    val current = readEditableOverride(db)
                    val expiresIso = current.traktTokenExpiresAt?.takeIf { it.isNotBlank() }
                        ?: settings.traktTokenExpiresAt?.takeIf { it.isNotBlank() }
                    if (expiresIso == null) {
                        JobOutcome(summary = "no expiry set", noop = true)
                    } else {
                        val expires = OffsetDateTime.parse(expiresIso).toInstant()
                        val daysLeft = Duration.between(Instant.now(), expires).toDays()
                        if (daysLeft > 7) {
                            JobOutcome(summary = "$daysLeft days left, no refresh needed", noop = true)
                        } else {
                            logger.info("trakt-refresh: token expires in {} days; proactively refreshing", daysLeft)
                            val newToken = refreshTraktTokensFromDb(db, settings, traktOAuth)
                            if (newToken != null) {
                                traktClient.updateAccessToken(newToken)
                                JobOutcome(summary = "refreshed (was $daysLeft days from expiry)")
                            } else {
                                JobOutcome(summary = "refresh failed; tokens cleared, awaits reconnect")
                            }
                        }
                    }
                }
                delay(liveSettings(db, settings).intervals.traktTokenRefreshHours.toLong() * 60L * 60L * 1000L)
            }
        }
    }

    // Watched archiver — deletes watched episodes outside the keep
    // window (latest season or last N). Off by default. Cadence is
    // configurable via settings.archive.intervalHours (default weekly).
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("watched_archiver crashed", e) }) {
        delay(10L * 60L * 1000L)  // stagger 10 min after boot
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "watched-archiver") {
                if (!s.archive.watchedEnabled) {
                    JobOutcome(summary = "watched_enabled=false", noop = true)
                } else {
                    val report = watchedArchiver.sweep(s.archive, dryRun = s.dryRun)
                    JobOutcome(summary = "${report.seriesVisited} series, ${report.deleted} deleted")
                }
            }
            delay(s.archive.intervalHours.toLong() * 60L * 60L * 1000L)
        }
    }

    // Orphan reaper — sweeps download folders for files Sonarr/SAB
    // no longer track.
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("orphan_reaper crashed", e) }) {
        delay(5L * 60L * 1000L)
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "orphan-reaper") {
                orphanReaper.sweep(dryRun = s.dryRun)
                JobOutcome()
            }
            delay(s.orphanReaperIntervalMinutes.toLong() * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("backfill_sweep crashed", e) }) {
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "backfill-sweep") {
                org.yoshiz.app.prioritarr.backend.sweep.runBackfillSweep(
                    sonarr, priorityService,
                    maxSearches = s.intervals.backfillMaxSearchesPerSweep,
                    delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                    dryRun = s.dryRun,
                )
                JobOutcome()
            }
            delay(s.intervals.backfillSweepHours.toLong() * 60L * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("cutoff_sweep crashed", e) }) {
        while (isActive) {
            val s = liveSettings(db, settings)
            trackJob(db, "cutoff-sweep") {
                org.yoshiz.app.prioritarr.backend.sweep.runCutoffSweep(
                    sonarr, priorityService,
                    maxSearches = s.intervals.cutoffMaxSearchesPerSweep,
                    delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                    dryRun = s.dryRun,
                )
                JobOutcome()
            }
            delay(s.intervals.cutoffSweepHours.toLong() * 60L * 60L * 1000L)
        }
    }

    embeddedServer(Netty, port = 8000, host = "0.0.0.0") {
        prioritarrModule(state)
    }.start(wait = true)
}
