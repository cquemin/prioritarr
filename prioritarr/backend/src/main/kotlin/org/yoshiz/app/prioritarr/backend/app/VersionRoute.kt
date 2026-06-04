package org.yoshiz.app.prioritarr.backend.app

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.yoshiz.app.prioritarr.backend.schemas.VersionResponse

/**
 * Top-level, unauthenticated build-provenance endpoint — sits beside
 * /health and /openapi.json (NOT under /api/v2), so it carries no
 * api_key and is outside the openapi contract.
 */
fun Route.versionRoute() {
    get("/version") {
        call.respond(
            VersionResponse(
                version = BuildInfo.version,
                gitSha = BuildInfo.gitSha,
                buildTime = BuildInfo.buildTime,
            ),
        )
    }
}
