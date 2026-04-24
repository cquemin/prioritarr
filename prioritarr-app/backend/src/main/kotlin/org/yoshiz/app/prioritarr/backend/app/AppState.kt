package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.HttpClient
import org.yoshiz.app.prioritarr.backend.clients.DownloadClient
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.yoshiz.app.prioritarr.backend.config.Settings
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.events.EventBus
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import org.yoshiz.app.prioritarr.backend.priority.PriorityService
import org.yoshiz.app.prioritarr.backend.priority.ThresholdsSource
import org.yoshiz.app.prioritarr.backend.reconcile.OrphanReaper
import org.yoshiz.app.prioritarr.backend.sync.CrossSourceSync

/**
 * Plain holder for everything the route handlers need. Built once at
 * startup, passed into the Ktor module function. Keeping this a data-class
 * wrapper instead of a global object makes tests easier — a test harness
 * can build an AppState with MockEngine-backed clients and an in-memory
 * Database.
 */
data class AppState(
    val settings: Settings,
    val db: Database,
    val sonarr: SonarrClient,
    val tautulli: TautulliClient,
    val qbit: QBitClient,
    val sab: SABClient,
    /**
     * Map of clientName -> DownloadClient so routes and the bulk
     * endpoint can dispatch generically. Populated in Main.kt from
     * the concrete qbit + sab instances; adding a third downloader
     * (Transmission, NZBGet) means dropping it into this map.
     */
    val downloadClients: Map<String, DownloadClient>,
    val mappings: MappingState,
    val priorityService: PriorityService,
    val thresholdsSource: ThresholdsSource,
    val crossSourceSync: CrossSourceSync,
    val orphanReaper: OrphanReaper,
    val eventBus: EventBus,
    val httpClients: List<HttpClient>,
)
