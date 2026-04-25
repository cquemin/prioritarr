package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.TraktClient
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Pulls episode watch history from Trakt.tv.
 *
 * Uses the series' TVDB id to resolve a Trakt show id (one search call
 * per series, cached in-memory for the process lifetime). Shows with
 * no TVDB id on the Sonarr side are skipped and return an empty list.
 *
 * A null mapping (TVDB id exists but Trakt doesn't know that show) is
 * also cached — so we don't repeat a fruitless search every priority
 * refresh cycle. If Trakt's coverage changes, a container restart
 * re-discovers.
 *
 * Source value on emitted events: "trakt".
 */
class TraktHistoryProvider(
    private val trakt: TraktClient,
) : WatchHistoryProvider {

    override val name: String = "trakt"
    private val logger = LoggerFactory.getLogger(TraktHistoryProvider::class.java)

    // tvdbId -> trakt_show_id (null means "looked up, not found").
    // Size is bounded by the Sonarr library size (hundreds, not
    // millions), so a plain map is fine — no eviction needed.
    private val idCache = ConcurrentHashMap<Long, OptionalId>()

    private data class OptionalId(val traktId: Long?)

    override suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>> = runCatching {
        val tvdb = ref.tvdbId ?: return@runCatching emptyList()

        val cached = idCache[tvdb]
        val traktShowId: Long? = if (cached != null) {
            cached.traktId
        } else {
            val resolved = try {
                trakt.searchShowByTvdb(tvdb)
            } catch (e: Exception) {
                logger.info("trakt search failed for tvdb={} ({}): {}", tvdb, ref.title, e.message)
                throw e
            }
            idCache[tvdb] = OptionalId(resolved)
            if (resolved == null) {
                logger.debug("trakt: no show found for tvdb={} ({})", tvdb, ref.title)
            }
            resolved
        }
        if (traktShowId == null) return@runCatching emptyList()

        val raw: JsonArray = trakt.getShowHistory(traktShowId, limit = 1000)

        raw.mapNotNull { el ->
            val obj = el.jsonObject
            val episodeObj = obj["episode"]?.jsonObject ?: return@mapNotNull null
            val season = episodeObj["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val episode = episodeObj["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val absoluteEpisode = episodeObj["number_abs"]?.jsonPrimitive?.intOrNull
            val watchedAtStr = obj["watched_at"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val watchedAt = try {
                OffsetDateTime.parse(watchedAtStr).toInstant()
            } catch (_: Exception) { return@mapNotNull null }
            WatchEvent(
                season = season,
                episode = episode,
                watchedAt = watchedAt,
                source = name,
                absoluteEpisode = absoluteEpisode,
            )
        }
    }.onFailure {
        logger.info("trakt history failed for series {}: {}", ref.seriesId, it.message)
    }
}
