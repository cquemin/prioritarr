package org.yoshiz.app.prioritarr.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.Principal
import io.ktor.server.request.header
import io.ktor.server.request.path
import org.yoshiz.app.prioritarr.backend.errors.ProblemDetail
import org.yoshiz.app.prioritarr.backend.errors.respondProblem

/** Marker for a successfully-auth'd request; no identity concept yet. */
data class ApiKeyPrincipal(val key: String) : Principal

/**
 * Accepts either:
 * - `X-Api-Key: <key>`
 * - `Authorization: Bearer <key>`
 *
 * When [Config.expectedKey] is null (no PRIORITARR_API_KEY set), auth is a
 * no-op and every request passes with a synthetic "(no-auth)" principal.
 * The [org.yoshiz.app.prioritarr.backend.app.Module] logs a WARN at startup
 * for the disabled case.
 */
class ApiKeyAuthenticationProvider(config: Config) : AuthenticationProvider(config) {
    private val expectedKey: String? = config.expectedKey

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        var expectedKey: String? = null
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        if (expectedKey == null) {
            context.principal(ApiKeyPrincipal("(no-auth)"))
            return
        }
        val provided = extractKey(call)
        if (provided == expectedKey) {
            context.principal(ApiKeyPrincipal(expectedKey))
            return
        }
        val cause = if (provided == null) AuthenticationFailedCause.NoCredentials
                    else AuthenticationFailedCause.InvalidCredentials
        context.challenge("apiKey", cause) { challenge, rejectedCall ->
            rejectedCall.respondProblem(
                ProblemDetail(
                    type = "/errors/unauthorized",
                    title = "Unauthorized",
                    status = HttpStatusCode.Unauthorized.value,
                    detail = "Missing or invalid X-Api-Key header.",
                    instance = rejectedCall.request.path(),
                ),
                HttpStatusCode.Unauthorized,
            )
            challenge.complete()
        }
    }

    private fun extractKey(call: ApplicationCall): String? {
        call.request.header("X-Api-Key")?.let { return it }
        val authz = call.request.header("Authorization") ?: return null
        return authz.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() && it != authz }
    }
}

fun AuthenticationConfig.apiKey(
    name: String = "api_key",
    configure: ApiKeyAuthenticationProvider.Config.() -> Unit,
) {
    val cfg = ApiKeyAuthenticationProvider.Config(name).apply(configure)
    register(ApiKeyAuthenticationProvider(cfg))
}
