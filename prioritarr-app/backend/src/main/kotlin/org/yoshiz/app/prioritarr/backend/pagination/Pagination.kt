package org.yoshiz.app.prioritarr.backend.pagination

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.toMap
import kotlinx.serialization.Serializable
import org.yoshiz.app.prioritarr.backend.errors.ValidationException

enum class SortDir { ASC, DESC }

data class PageParams(
    val offset: Int,
    val limit: Int,
    val sort: String,
    val sortDir: SortDir,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 1000
    }
}

/**
 * Generic envelope matching arr's list-response shape. Generic slot on
 * [records] so each endpoint can supply its own record type without
 * stringly-typed serializer wiring.
 */
@Serializable
data class PaginatedEnvelope<T>(
    val records: List<T>,
    val totalRecords: Int,
    val offset: Int,
    val limit: Int,
)

/**
 * Parse `offset`, `limit`, `sort`, `sort_dir` from [call]. [allowedSorts] is
 * the list the endpoint supports; an unknown sort field → [ValidationException].
 */
fun pageParamsFrom(
    call: ApplicationCall,
    allowedSorts: Set<String>,
    defaultSort: String,
    defaultDir: SortDir = SortDir.ASC,
): PageParams {
    val q = call.request.queryParameters
    val offset = q["offset"]?.toIntOrNull() ?: 0
    if (offset < 0) throw ValidationException("offset", "must be >= 0")

    val limit = q["limit"]?.toIntOrNull() ?: PageParams.DEFAULT_LIMIT
    if (limit < 1 || limit > PageParams.MAX_LIMIT) {
        throw ValidationException("limit", "must be between 1 and ${PageParams.MAX_LIMIT}")
    }

    val sort = q["sort"] ?: defaultSort
    if (sort !in allowedSorts) {
        throw ValidationException("sort", "must be one of ${allowedSorts.joinToString(", ")}")
    }

    val sortDirRaw = q["sort_dir"]
    val sortDir = when (sortDirRaw?.lowercase()) {
        null -> defaultDir
        "asc" -> SortDir.ASC
        "desc" -> SortDir.DESC
        else -> throw ValidationException("sort_dir", "must be asc or desc")
    }

    return PageParams(offset, limit, sort, sortDir)
}

/** Apply [params] to an already-fetched list, returning the paged slice + totalRecords. */
fun <T> paginate(all: List<T>, params: PageParams): PaginatedEnvelope<T> =
    PaginatedEnvelope(
        records = all.drop(params.offset).take(params.limit),
        totalRecords = all.size,
        offset = params.offset,
        limit = params.limit,
    )
