package org.yoshiz.app.prioritarr.backend.clients

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Thin adapter over [SonarrClient] that conforms to
 * [MediaCatalog]. Read-only view: translates Sonarr's native JSON
 * responses into typed [MediaItem.Series] records.
 *
 * Intended as the pattern a [RadarrCatalog] implementation should
 * follow — wrap the existing client, project JSON to the typed
 * domain, keep the interface surface tiny. The rest of the app
 * still talks to [SonarrClient] directly for the many Sonarr-
 * specific operations (episodes, manualimport, queue, commands)
 * that don't generalise cleanly to movies; the catalog interface
 * covers only the generic "list + lookup" bit.
 */
class SonarrCatalog(private val sonarr: SonarrClient) : MediaCatalog<MediaItem.Series> {
    override val catalogName: String = "sonarr"

    override suspend fun listAll(): List<MediaItem.Series> =
        sonarr.getAllSeries().mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            MediaItem.Series(
                id = id,
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                tvdbId = obj["tvdbId"]?.jsonPrimitive?.longOrNull,
            )
        }

    override suspend fun get(id: Long): MediaItem.Series? = try {
        val obj = sonarr.getSeries(id)
        MediaItem.Series(
            id = obj["id"]?.jsonPrimitive?.longOrNull ?: id,
            title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            tvdbId = obj["tvdbId"]?.jsonPrimitive?.longOrNull,
        )
    } catch (_: Exception) { null }
}
