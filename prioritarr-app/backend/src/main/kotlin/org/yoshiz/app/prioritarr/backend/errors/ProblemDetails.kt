package org.yoshiz.app.prioritarr.backend.errors

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * RFC 7807 Problem Details body.
 *
 * Only the four required fields plus the optional [instance] path.
 * Additional members (e.g. `invalid-params`) can be added by returning
 * a subclass — kotlinx.serialization honours the subclass shape when
 * called on a concrete type.
 */
@Serializable
open class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String? = null,
)

/** Tag type carried on exceptions so the StatusPages handler can route. */
sealed class PrioritarrException(
    val slug: String,
    val httpStatus: HttpStatusCode,
    val title: String,
    override val message: String,
) : RuntimeException(message)

class UnauthorizedException(message: String = "Missing or invalid API key.") :
    PrioritarrException("unauthorized", HttpStatusCode.Unauthorized, "Unauthorized", message)

class NotFoundException(message: String) :
    PrioritarrException("not-found", HttpStatusCode.NotFound, "Not Found", message)

class ValidationException(field: String, reason: String) :
    PrioritarrException("validation", HttpStatusCode.UnprocessableEntity,
        "Validation", "$field: $reason")

class UpstreamUnreachableException(upstream: String, cause: String) :
    PrioritarrException("upstream-unreachable", HttpStatusCode.BadGateway,
        "Upstream unreachable", "$upstream: $cause")

class InternalException(cause: String) :
    PrioritarrException("internal", HttpStatusCode.InternalServerError,
        "Internal Server Error", cause)

fun PrioritarrException.toProblem(instance: String?): ProblemDetail = ProblemDetail(
    type = "/errors/$slug",
    title = title,
    status = httpStatus.value,
    detail = message,
    instance = instance,
)

/** Write a Problem response with the application/problem+json content type. */
suspend fun ApplicationCall.respondProblem(
    problem: ProblemDetail,
    httpStatus: HttpStatusCode = HttpStatusCode.fromValue(problem.status),
) {
    respondText(
        text = problemJson.encodeToString(problem),
        contentType = ContentType("application", "problem+json"),
        status = httpStatus,
    )
}

internal val problemJson = Json { encodeDefaults = true; explicitNulls = false }
