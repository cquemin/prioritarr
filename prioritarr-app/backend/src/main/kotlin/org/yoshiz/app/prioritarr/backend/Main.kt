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
import org.yoshiz.app.prioritarr.backend.mapping.InMemoryMappingCache
import org.yoshiz.app.prioritarr.backend.mapping.LettuceMappingCache
import org.yoshiz.app.prioritarr.backend.mapping.MappingCache
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

    val sonarr = SonarrClient(settings.sonarrUrl, settings.sonarrApiKey, sonarrHttp)
    val tautulli = TautulliClient(settings.tautulliUrl, settings.tautulliApiKey, tautulliHttp)
    val qbit = QBitClient(settings.qbitUrl, settings.qbitUsername.orEmpty(), settings.qbitPassword.orEmpty(), qbitHttp)
    val sab = SABClient(settings.sabUrl, settings.sabApiKey, sabHttp)

    val mappings = MappingState()

    val cache: MappingCache = settings.redisUrl?.let { LettuceMappingCache(it) } ?: InMemoryMappingCache()
    // Re-hydrate the plex-key map at startup so webhooks work before the
    // first refresh. TVDB + title maps fill in on the next refresh cycle.
    mappings.hydrate(Hydrate.seed(cache.load()))

    val priorityService = PriorityService(
        sonarr = sonarr,
        tautulli = tautulli,
        db = db,
        mappings = mappings,
        thresholds = settings.priorityThresholds,
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
        eventBus = EventBus(),
        httpClients = listOf(sonarrHttp, tautulliHttp, plexHttp, qbitHttp, sabHttp),
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

    embeddedServer(Netty, port = 8000, host = "0.0.0.0") {
        prioritarrModule(state)
    }.start(wait = true)
}
