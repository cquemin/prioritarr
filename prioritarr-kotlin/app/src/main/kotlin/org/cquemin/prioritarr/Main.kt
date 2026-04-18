package org.cquemin.prioritarr

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cquemin.prioritarr.app.AppState
import org.cquemin.prioritarr.app.prioritarrModule
import org.cquemin.prioritarr.clients.QBitClient
import org.cquemin.prioritarr.clients.SABClient
import org.cquemin.prioritarr.clients.SonarrClient
import org.cquemin.prioritarr.clients.TautulliClient
import org.cquemin.prioritarr.config.loadSettingsFromEnv
import org.cquemin.prioritarr.database.Database
import org.cquemin.prioritarr.http.defaultJsonClient
import org.cquemin.prioritarr.http.qbitClient
import org.cquemin.prioritarr.http.xmlClient
import org.cquemin.prioritarr.mapping.Hydrate
import org.cquemin.prioritarr.mapping.InMemoryMappingCache
import org.cquemin.prioritarr.mapping.LettuceMappingCache
import org.cquemin.prioritarr.mapping.MappingCache
import org.cquemin.prioritarr.mapping.MappingState
import org.cquemin.prioritarr.mapping.hydrate
import org.cquemin.prioritarr.priority.PriorityService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.cquemin.prioritarr.Main")

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

    embeddedServer(Netty, port = 8000, host = "0.0.0.0") {
        prioritarrModule(state)
    }.start(wait = true)
}
