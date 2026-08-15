package org.yoshiz.app.prioritarr.backend.health

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.config.Settings
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.schemas.ProviderStatus

/**
 * Probes the upstreams Prioritarr reads from and writes a single
 * snapshot row per provider into [Database.upsertProviderHealth].
 * The dashboard banner reads from that table — no historical retention.
 *
 * Lifecycle: registered as a LIGHT scheduler job (cadence ~5 min,
 * weight LIGHT). The scheduler tick invokes [probeAll]; this class
 * doesn't own any timers.
 *
 * Scope (Phase 2a): the six providers Prioritarr currently depends on
 * — Sonarr, Tautulli, qBit, SAB, Plex, Trakt. Bazarr / Whisper /
 * Lingarr arrive with the subtitle orchestrator (Phase 2c).
 *
 * Design notes:
 *   - Each probe is HTTP-only and tiny (one GET, no body parsing).
 *     We don't reuse the heavy clients because their methods are
 *     full-fat data calls; a 1-byte 200 is all we need.
 *   - 401/403 maps to UNAUTH so the banner can show a "click to
 *     re-auth" CTA, distinct from "service down".
 *   - Connection / timeout errors map to UNREACHABLE — the user
 *     usually can't fix that from settings.
 *   - We never throw out of [probeAll]; one bad provider can't take
 *     out the whole probe pass.
 */
class HealthMonitor(
    private val db: Database,
    private val settingsProvider: () -> Settings,
    private val http: HttpClient,
    /**
     * When Trakt's rate-limit breaker is open, this returns the instant it
     * clears. The health probe is one cheap call and sails through a limit
     * that is failing hundreds of real history fetches, so without this
     * signal the dashboard reports "ok" during an outage that is actively
     * degrading priority scoring.
     */
    private val traktRateLimitedUntil: () -> java.time.Instant? = { null },
) {

    private val logger = LoggerFactory.getLogger(HealthMonitor::class.java)

    /** What lands in `provider_health.detail` and the API payload. */
    private data class Probe(val status: ProviderStatus, val detail: String?)

    /**
     * Run every probe (in parallel) and persist results. Returns the
     * count of providers in non-OK state for the scheduler summary.
     *
     * Settings are snapshotted once per pass via [settingsProvider]
     * (typically `liveSettings(db, baseline)`) and passed into each
     * probe — that way a credential that changes at runtime, notably
     * a Trakt access_token minted by the refresh flow and persisted to
     * the DB override (not the boot Settings), is picked up on the
     * next pass instead of staying stale until a restart. Passing
     * `Settings` as a `val` parameter (vs. a mutable field) also keeps
     * Kotlin smart-casts working inside each probe.
     */
    suspend fun probeAll(): Int = coroutineScope {
        val settings = settingsProvider()
        val now = Database.nowIsoOffset()
        val results = listOf(
            async { "sonarr" to probeSonarr(settings) },
            async { "tautulli" to probeTautulli(settings) },
            async { "qbit" to probeQbit(settings) },
            async { "sab" to probeSab(settings) },
            async { "plex" to probePlex(settings) },
            async { "trakt" to probeTrakt(settings) },
        ).awaitAll()

        var unhealthy = 0
        for ((provider, probe) in results) {
            val statusWire = serialName(probe.status)
            // last_ok bump is "only when status==ok" — pass null otherwise
            // so the SQL COALESCE preserves the previous good timestamp.
            val lastOk = if (probe.status == ProviderStatus.OK) now else null
            db.upsertProviderHealth(
                provider = provider,
                status = statusWire,
                lastOk = lastOk,
                lastCheck = now,
                detail = probe.detail,
            )
            if (probe.status != ProviderStatus.OK) unhealthy++
        }
        unhealthy
    }

    // ------------------------------------------------------------------
    // per-provider probes
    // ------------------------------------------------------------------

    private suspend fun probeSonarr(s: Settings): Probe {
        if (s.sonarrUrl.isBlank() || s.sonarrApiKey.isBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        return probeWithApiKeyHeader(
            url = "${s.sonarrUrl.trimEnd('/')}/api/v3/system/status",
            keyHeader = "X-Api-Key",
            keyValue = s.sonarrApiKey,
        )
    }

    private suspend fun probeTautulli(s: Settings): Probe {
        if (s.tautulliUrl.isNullOrBlank() || s.tautulliApiKey.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        // Tautulli auths via apikey query param. `cmd=arnold` returns a
        // famous quote — cheap, no library access, clear 200/401 split.
        return probeRaw(
            url = "${s.tautulliUrl.trimEnd('/')}/api/v2",
            params = mapOf("apikey" to s.tautulliApiKey, "cmd" to "arnold"),
        )
    }

    private suspend fun probeQbit(s: Settings): Probe {
        if (s.qbitUrl.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        // Unauthenticated probe — just confirms qBit is reachable. Auth
        // is checked separately by the existing QBitClient on first use.
        return probeRaw(
            url = "${s.qbitUrl.trimEnd('/')}/api/v2/app/version",
        )
    }

    private suspend fun probeSab(s: Settings): Probe {
        if (s.sabUrl.isNullOrBlank() || s.sabApiKey.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        return probeRaw(
            url = "${s.sabUrl.trimEnd('/')}/api",
            params = mapOf("apikey" to s.sabApiKey, "mode" to "version", "output" to "json"),
        )
    }

    private suspend fun probePlex(s: Settings): Probe {
        if (s.plexUrl.isNullOrBlank() || s.plexToken.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        // /identity is unauthenticated, just confirms reachability. Use
        // /myplex/account when token validation matters; for the banner
        // we keep it cheap and let real calls surface auth issues.
        return probeRaw(
            url = "${s.plexUrl.trimEnd('/')}/identity",
        )
    }

    private suspend fun probeTrakt(s: Settings): Probe {
        // Token expiry is the canonical failure mode. /users/settings
        // requires a valid access_token and Trakt API headers.
        val accessToken = s.traktAccessToken?.takeIf { it.isNotBlank() }
            ?: return Probe(ProviderStatus.UNKNOWN, "not configured")
        val clientId = s.traktClientId?.takeIf { it.isNotBlank() }
            ?: return Probe(ProviderStatus.UNKNOWN, "client_id missing")
        // Report the breaker before probing: a 429 that is failing real
        // workload must not look healthy just because one extra call fits.
        traktRateLimitedUntil()?.let { until ->
            if (java.time.Instant.now().isBefore(until)) {
                return Probe(ProviderStatus.UNREACHABLE, "rate limited until $until")
            }
        }
        return runCatching {
            val resp: HttpResponse = http.get("https://api.trakt.tv/users/settings") {
                header("Authorization", "Bearer $accessToken")
                header("trakt-api-version", "2")
                header("trakt-api-key", clientId)
            }
            classify(resp.status)
        }.getOrElse { e -> Probe(ProviderStatus.UNREACHABLE, e.message ?: e::class.simpleName) }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** GET with X-Api-Key (Sonarr-style). 401/403 → UNAUTH. */
    private suspend fun probeWithApiKeyHeader(url: String, keyHeader: String, keyValue: String): Probe =
        runCatching {
            val resp: HttpResponse = http.get(url) { header(keyHeader, keyValue) }
            classify(resp.status)
        }.getOrElse { e -> Probe(ProviderStatus.UNREACHABLE, e.message ?: e::class.simpleName) }

    /** GET with optional query params. */
    private suspend fun probeRaw(url: String, params: Map<String, String> = emptyMap()): Probe =
        runCatching {
            val resp: HttpResponse = http.get(url) {
                for ((k, v) in params) parameter(k, v)
            }
            classify(resp.status)
        }.getOrElse { e -> Probe(ProviderStatus.UNREACHABLE, e.message ?: e::class.simpleName) }

    private fun classify(status: HttpStatusCode): Probe = when {
        status.value in 200..299 -> Probe(ProviderStatus.OK, null)
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            Probe(ProviderStatus.UNAUTH, "HTTP ${status.value} ${status.description}")
        else -> Probe(ProviderStatus.UNREACHABLE, "HTTP ${status.value} ${status.description}")
    }

    private fun serialName(s: ProviderStatus): String = when (s) {
        ProviderStatus.OK -> "ok"
        ProviderStatus.UNAUTH -> "unauth"
        ProviderStatus.UNREACHABLE -> "unreachable"
        ProviderStatus.UNKNOWN -> "unknown"
    }
}
