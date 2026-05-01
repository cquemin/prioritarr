package org.yoshiz.app.prioritarr.backend.api.v2

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.yoshiz.app.prioritarr.backend.app.AppState
import org.yoshiz.app.prioritarr.backend.schemas.HealthProvidersResponse
import org.yoshiz.app.prioritarr.backend.schemas.ProviderHealthEntry
import org.yoshiz.app.prioritarr.backend.schemas.ProviderStatus

/**
 * GET /api/v2/health/providers — snapshot of every probed upstream's
 * status. Read-only view of `provider_health`. The dashboard banner
 * polls this every minute; HealthMonitor populates the table every
 * 5 minutes via the scheduler.
 *
 * The label + settingsAnchor for each provider live here (server side)
 * so the UI's deep-link target stays in sync with what the banner says.
 * If we ever rename a settings section, only this file changes.
 */
fun Route.healthProvidersRoute(state: AppState) {
    get("/health/providers") {
        val rows = state.db.listProviderHealth()
        val byProvider = rows.associateBy { it.provider }

        val entries = PROVIDER_METADATA.map { meta ->
            val row = byProvider[meta.id]
            ProviderHealthEntry(
                name = meta.id,
                label = meta.label,
                settingsAnchor = meta.settingsAnchor,
                status = row?.status?.let(::statusFromWire) ?: ProviderStatus.UNKNOWN,
                detail = row?.detail,
                lastOkAt = row?.last_ok,
                lastCheckAt = row?.last_check ?: "",
            )
        }

        call.respond(HealthProvidersResponse(providers = entries))
    }
}

/**
 * Static metadata keyed off the provider id written by [HealthMonitor].
 * Order here is the order entries appear in the API response (and thus
 * the banner's stack order). Matches the provider list in
 * Constants.kt's [org.yoshiz.app.prioritarr.backend.ConnectionService].
 */
private data class ProviderMeta(val id: String, val label: String, val settingsAnchor: String)

private val PROVIDER_METADATA = listOf(
    ProviderMeta("sonarr", "Sonarr", "sonarr"),
    ProviderMeta("tautulli", "Tautulli", "tautulli"),
    ProviderMeta("plex", "Plex", "plex"),
    ProviderMeta("trakt", "Trakt", "trakt"),
    ProviderMeta("qbit", "qBittorrent", "qbit"),
    ProviderMeta("sab", "SABnzbd", "sab"),
)

private fun statusFromWire(wire: String): ProviderStatus = when (wire) {
    "ok" -> ProviderStatus.OK
    "unauth" -> ProviderStatus.UNAUTH
    "unreachable" -> ProviderStatus.UNREACHABLE
    else -> ProviderStatus.UNKNOWN
}
