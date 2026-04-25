package org.yoshiz.app.prioritarr.backend.app

import io.ktor.client.HttpClient
import org.yoshiz.app.prioritarr.backend.clients.DownloadClient
import org.yoshiz.app.prioritarr.backend.enforcement.BandwidthSource
import org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.yoshiz.app.prioritarr.backend.clients.TraktClient
import org.yoshiz.app.prioritarr.backend.clients.TraktOAuth
import org.yoshiz.app.prioritarr.backend.config.Settings
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.events.EventBus
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import org.yoshiz.app.prioritarr.backend.priority.PriorityService
import org.yoshiz.app.prioritarr.backend.priority.ThresholdsSource
import org.yoshiz.app.prioritarr.backend.reconcile.OrphanReaper
import org.yoshiz.app.prioritarr.backend.reconcile.TraktUnmonitorReconciler
import org.yoshiz.app.prioritarr.backend.reconcile.WatchedArchiver
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
    /** Rolling qBit speed samples used by the bandwidth-aware enforcement. */
    val downloadTelemetry: DownloadTelemetry,
    /** Live-editable bandwidth settings. Reconciler reads this each tick. */
    val bandwidthSource: BandwidthSource,
    val mappings: MappingState,
    val priorityService: PriorityService,
    val thresholdsSource: ThresholdsSource,
    val crossSourceSync: CrossSourceSync,
    val orphanReaper: OrphanReaper,
    val watchedArchiver: WatchedArchiver,
    val traktUnmonitor: TraktUnmonitorReconciler,
    /** Live TraktClient — null when Trakt isn't configured. Routes use it for hot-swapping the access token after refresh. */
    val traktClient: TraktClient?,
    /** OAuth helper — null when client_id or client_secret missing. Routes use it for begin/poll/refresh. */
    val traktOAuth: TraktOAuth?,
    val eventBus: EventBus,
    val httpClients: List<HttpClient>,
)
