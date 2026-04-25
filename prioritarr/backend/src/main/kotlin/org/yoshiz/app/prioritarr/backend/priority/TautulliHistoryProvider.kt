package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import org.yoshiz.app.prioritarr.backend.mapping.normaliseTitle
import java.time.Instant

/**
 * Pulls episode watch history from Tautulli. Prefers the fast lookup
 * via grandparent_rating_key (via the mapping table — plex_key for
 * the series is already resolved by refresh_mappings). Falls back to
 * a bulk history fetch + title-match filter when no mapping exists.
 *
 * Source value on emitted events: "tautulli".
 */
class TautulliHistoryProvider(
    private val tautulli: TautulliClient,
    private val mappings: MappingState,
) : WatchHistoryProvider {

    override val name: String = "tautulli"
    private val logger = LoggerFactory.getLogger(TautulliHistoryProvider::class.java)

    override suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>> = runCatching {
        val plexKey = mappings.plexKeyForSeriesTitle(ref.title)
        val raw: JsonArray = if (plexKey != null) {
            tautulli.getHistory(grandparentRatingKey = plexKey, mediaType = "episode", length = 500)
        } else {
            // Fallback — ~2k rows then filter by title. Slow but covers
            // shows the mapping job hasn't resolved yet (new Plex adds,
            // title-only matches, etc.).
            val all = tautulli.getHistory(mediaType = "episode", length = 2000)
            val norm = normaliseTitle(ref.title)
            JsonArray(all.filter {
                normaliseTitle(it.jsonObject["grandparent_title"]?.jsonPrimitive?.contentOrNull.orEmpty()) == norm
            })
        }

        raw.mapNotNull { el ->
            val obj = el.jsonObject
            val season = obj["parent_media_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val episode = obj["media_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val ts = obj["date"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            WatchEvent(
                season = season,
                episode = episode,
                watchedAt = Instant.ofEpochSecond(ts),
                source = name,
            )
        }
    }.onFailure {
        logger.info("tautulli history failed for series {}: {}", ref.seriesId, it.message)
    }
}
