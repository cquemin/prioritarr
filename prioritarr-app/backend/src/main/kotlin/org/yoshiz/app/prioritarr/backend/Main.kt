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

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.Main")

fun main() {
    val settings = loadSettingsFromEnv()
    logger.info("prioritarr (kotlin) starting (dry_run={}, test_mode={})", settings.dryRun, settings.testMode)

    val db = Database("/config/state.db")

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
    val traktClient: org.yoshiz.app.prioritarr.backend.clients.TraktClient? =
        if (settings.traktClientId != null && settings.traktAccessToken != null) {
            org.yoshiz.app.prioritarr.backend.clients.TraktClient(
                clientId = settings.traktClientId,
                accessToken = settings.traktAccessToken,
                http = traktHttp,
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

    val state = AppState(
        settings = settings,
        db = db,
        sonarr = sonarr,
        tautulli = tautulli,
        qbit = qbit,
        sab = sab,
        mappings = mappings,
        priorityService = priorityService,
        thresholdsSource = thresholdsSource,
        crossSourceSync = crossSourceSync,
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

    // Four periodic background jobs — refresh_mappings, reconcile
    //   (qbit + sab in one job), backfill_sweep, cutoff_sweep. Each runs
    //   in its own coroutine under the supervisor job so one crashing
    //   doesn't take the others down.
    val intervals = settings.intervals
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_mappings crashed", e) }) {
        while (isActive) {
            try {
                org.yoshiz.app.prioritarr.backend.mapping.refreshMappings(sonarr, tautulli, cache, mappings)
                state.eventBus.publish("mapping-refreshed", kotlinx.serialization.json.JsonNull)
            } catch (e: Exception) { logger.warn("refresh_mappings: {}", e.message) }
            delay(60L * 60L * 1000L)  // hourly
        }
    }
    // Series-cache refresh is our read-model sync. First run is eager
    // so the UI is populated within seconds of container boot; then
    // every 5 minutes to pick up series adds/removes from Sonarr.
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_series_cache crashed", e) }) {
        while (isActive) {
            try {
                org.yoshiz.app.prioritarr.backend.series.refreshSeriesCache(sonarr, db)
            } catch (e: Exception) { logger.warn("refresh_series_cache: {}", e.message) }
            delay(5L * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("reconcile crashed", e) }) {
        while (isActive) {
            try {
                val lookup = org.yoshiz.app.prioritarr.backend.reconcile.fetchSonarrQueueLookup(sonarr)
                org.yoshiz.app.prioritarr.backend.reconcile.reconcileQbit(qbit, db, lookup, priorityService, settings.dryRun)
                org.yoshiz.app.prioritarr.backend.reconcile.reconcileSab(sab, db, lookup, priorityService, settings.dryRun)
            } catch (e: Exception) { logger.warn("reconcile: {}", e.message) }
            delay(intervals.reconcileMinutes * 60L * 1000L)
        }
    }
    // Full-library priority refresh — walks every monitored Sonarr series
    // every 30 min and triggers priorityForSeries. Cache TTL in the
    // service keeps the upstream traffic bounded; this job exists so
    // the UI has a priority value for every series, not just ones with
    // pending downloads or recent webhook hits.
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("refresh_priorities crashed", e) }) {
        while (isActive) {
            try {
                org.yoshiz.app.prioritarr.backend.priority.refreshAllPriorities(sonarr, priorityService)
            } catch (e: Exception) { logger.warn("refresh_priorities: {}", e.message) }
            delay(30L * 60L * 1000L)
        }
    }
    // Queue janitor — every 30 min: detect stuck torrents (no activity
    // > 48h) + failed/stuck SAB jobs, remove them, blocklist the
    // release in Sonarr, trigger an EpisodeSearch in priority order.
    val queueJanitor = org.yoshiz.app.prioritarr.backend.reconcile.QueueJanitor(
        sonarr = sonarr, qbit = qbit, sab = sab, db = db,
    )
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("queue_janitor crashed", e) }) {
        while (isActive) {
            try {
                queueJanitor.sweep(dryRun = settings.dryRun)
            } catch (e: Exception) { logger.warn("queue_janitor: {}", e.message) }
            delay(30L * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("backfill_sweep crashed", e) }) {
        while (isActive) {
            try {
                org.yoshiz.app.prioritarr.backend.sweep.runBackfillSweep(
                    sonarr, priorityService,
                    maxSearches = intervals.backfillMaxSearchesPerSweep,
                    delaySeconds = intervals.backfillDelayBetweenSearchesSeconds,
                    dryRun = settings.dryRun,
                )
            } catch (e: Exception) { logger.warn("backfill_sweep: {}", e.message) }
            delay(intervals.backfillSweepHours * 60L * 60L * 1000L)
        }
    }
    scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> logger.error("cutoff_sweep crashed", e) }) {
        while (isActive) {
            try {
                org.yoshiz.app.prioritarr.backend.sweep.runCutoffSweep(
                    sonarr, priorityService,
                    maxSearches = intervals.cutoffMaxSearchesPerSweep,
                    delaySeconds = intervals.backfillDelayBetweenSearchesSeconds,
                    dryRun = settings.dryRun,
                )
            } catch (e: Exception) { logger.warn("cutoff_sweep: {}", e.message) }
            delay(intervals.cutoffSweepHours * 60L * 60L * 1000L)
        }
    }

    embeddedServer(Netty, port = 8000, host = "0.0.0.0") {
        prioritarrModule(state)
    }.start(wait = true)
}
