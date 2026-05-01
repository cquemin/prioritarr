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
    private val settings: Settings,
    private val http: HttpClient,
) {

    private val logger = LoggerFactory.getLogger(HealthMonitor::class.java)

    /** What lands in `provider_health.detail` and the API payload. */
    private data class Probe(val status: ProviderStatus, val detail: String?)

    /**
     * Run every probe (in parallel) and persist results. Returns the
     * count of providers in non-OK state for the scheduler summary.
     */
    suspend fun probeAll(): Int = coroutineScope {
        val now = Database.nowIsoOffset()
        val results = listOf(
            async { "sonarr" to probeSonarr() },
            async { "tautulli" to probeTautulli() },
            async { "qbit" to probeQbit() },
            async { "sab" to probeSab() },
            async { "plex" to probePlex() },
            async { "trakt" to probeTrakt() },
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

    private suspend fun probeSonarr(): Probe {
        if (settings.sonarrUrl.isBlank() || settings.sonarrApiKey.isBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        return probeWithApiKeyHeader(
            url = "${settings.sonarrUrl.trimEnd('/')}/api/v3/system/status",
            keyHeader = "X-Api-Key",
            keyValue = settings.sonarrApiKey,
        )
    }

    private suspend fun probeTautulli(): Probe {
        if (settings.tautulliUrl.isNullOrBlank() || settings.tautulliApiKey.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        // Tautulli auths via apikey query param. `cmd=arnold` returns a
        // famous quote — cheap, no library access, clear 200/401 split.
        return probeRaw(
            url = "${settings.tautulliUrl.trimEnd('/')}/api/v2",
            params = mapOf("apikey" to settings.tautulliApiKey, "cmd" to "arnold"),
        )
    }

    private suspend fun probeQbit(): Probe {
        if (settings.qbitUrl.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        // Unauthenticated probe — just confirms qBit is reachable. Auth
        // is checked separately by the existing QBitClient on first use.
        return probeRaw(
            url = "${settings.qbitUrl.trimEnd('/')}/api/v2/app/version",
        )
    }

    private suspend fun probeSab(): Probe {
        if (settings.sabUrl.isNullOrBlank() || settings.sabApiKey.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        return probeRaw(
            url = "${settings.sabUrl.trimEnd('/')}/api",
            params = mapOf("apikey" to settings.sabApiKey, "mode" to "version", "output" to "json"),
        )
    }

    private suspend fun probePlex(): Probe {
        if (settings.plexUrl.isNullOrBlank() || settings.plexToken.isNullOrBlank()) {
            return Probe(ProviderStatus.UNKNOWN, "not configured")
        }
        // /identity is unauthenticated, just confirms reachability. Use
        // /myplex/account when token validation matters; for the banner
        // we keep it cheap and let real calls surface auth issues.
        return probeRaw(
            url = "${settings.plexUrl.trimEnd('/')}/identity",
        )
    }

    private suspend fun probeTrakt(): Probe {
        // Token expiry is the canonical failure mode. /users/settings
        // requires a valid access_token and Trakt API headers.
        val accessToken = settings.traktAccessToken?.takeIf { it.isNotBlank() }
            ?: return Probe(ProviderStatus.UNKNOWN, "not configured")
        val clientId = settings.traktClientId?.takeIf { it.isNotBlank() }
            ?: return Probe(ProviderStatus.UNKNOWN, "client_id missing")
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
