package org.cquemin.prioritarr.app

import io.ktor.client.HttpClient
import org.cquemin.prioritarr.clients.QBitClient
import org.cquemin.prioritarr.clients.SABClient
import org.cquemin.prioritarr.clients.SonarrClient
import org.cquemin.prioritarr.clients.TautulliClient
import org.cquemin.prioritarr.config.Settings
import org.cquemin.prioritarr.database.Database
import org.cquemin.prioritarr.mapping.MappingState
import org.cquemin.prioritarr.priority.PriorityService

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
    val mappings: MappingState,
    val priorityService: PriorityService,
    val httpClients: List<HttpClient>,
)
