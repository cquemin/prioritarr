package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-upstream health state surfaced by GET /api/v2/health/providers
 * and consumed by the dashboard banner. Distinct from the `/ready`
 * endpoint's [DependencyStatus] because the banner needs to
 * distinguish "auth expired, click to re-auth" from a generic
 * "unreachable" — the action and CTA are different.
 */
@Serializable
enum class ProviderStatus {
    @SerialName("ok") OK,

    /**
     * Upstream is reachable but rejected our credentials. Most common
     * cause: expired Trakt access token, rotated qBit/SAB password,
     * Plex token revoked. The banner CTA links to the matching
     * settings detail view so the user can re-auth.
     */
    @SerialName("unauth") UNAUTH,

    /**
     * TCP connect refused, DNS unresolvable, or HTTP timeout. Different
     * from UNAUTH because the user typically can't fix it from the
     * settings UI — the upstream itself is down.
     */
    @SerialName("unreachable") UNREACHABLE,

    /**
     * Probe hasn't run yet (first boot before scheduler tick) or the
     * provider isn't configured. Banner hides UNKNOWN entries.
     */
    @SerialName("unknown") UNKNOWN,
}

/**
 * One row in the banner. `settingsAnchor` matches the React route's
 * hash anchor for the corresponding settings detail view, so a click
 * deep-links the user to the form that lets them fix it.
 */
@Serializable
data class ProviderHealthEntry(
    /** Stable provider id (e.g. `"sonarr"`, `"plex"`, `"trakt"`). */
    val name: String,
    /** Human-readable label shown in the banner row. */
    val label: String,
    val status: ProviderStatus,
    /** Hash anchor in /settings to deep-link to (e.g. `"trakt"`). */
    val settingsAnchor: String,
    /** Free-form one-line diagnostic. Truncated to ~500 chars. */
    val detail: String?,
    /** ISO-8601 of the last successful probe, null if never. */
    val lastOkAt: String?,
    /** ISO-8601 of the most recent probe attempt. */
    val lastCheckAt: String,
)

@Serializable
data class HealthProvidersResponse(
    val providers: List<ProviderHealthEntry>,
)
