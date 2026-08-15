package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.TraktClient
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * How long a failed tvdb→trakt id lookup is remembered before we try
 * again. Bounds the blast radius of a transient upstream failure: with
 * a 300-series library and a 30-minute refresh cadence, retrying every
 * unresolved series every cycle is what sustains a rate-limit storm.
 */
const val TRAKT_ID_LOOKUP_BACKOFF_SECONDS: Long = 15 * 60

/**
 * Thrown instead of re-issuing a tvdb→trakt lookup that failed recently.
 * Surfaces as a provider failure (not "0 watched"), so the priority
 * service still degrades honestly rather than scoring on missing data.
 */
class TraktLookupBackoffException(
    tvdbId: Long,
    retryAt: Instant,
) : Exception("trakt id lookup for tvdb=$tvdbId backed off until $retryAt")

/** A resolved tvdb→trakt mapping. [traktId] null = Trakt has no such show. */
data class TraktIdEntry(val traktId: Long?)

/**
 * Durable home for tvdb→trakt id mappings.
 *
 * The mapping is immutable, so re-resolving it on every process start is
 * pure waste: a cold cache means one search call per series, which on a
 * few-hundred-series library is enough to trip Trakt's rate limit and
 * degrade priority scoring until it recovers.
 */
interface TraktIdStore {
    /** Cached entry, or null when this tvdb id has never been resolved. */
    fun lookup(tvdbId: Long): TraktIdEntry?
    fun save(tvdbId: Long, traktId: Long?)
}

/** [TraktIdStore] backed by the `trakt_id_cache` table. */
class DbTraktIdStore(
    private val db: org.yoshiz.app.prioritarr.backend.database.Database,
) : TraktIdStore {
    override fun lookup(tvdbId: Long): TraktIdEntry? =
        db.getTraktIdCache(tvdbId)?.let { TraktIdEntry(it.trakt_id) }

    override fun save(tvdbId: Long, traktId: Long?) {
        db.upsertTraktIdCache(tvdbId, traktId)
    }
}

/**
 * Pulls episode watch history from Trakt.tv.
 *
 * Uses the series' TVDB id to resolve a Trakt show id (one search call
 * per series). Resolutions are memoised in-process and, when a [store]
 * is wired, persisted — so a restart no longer re-resolves the whole
 * library. Shows with no TVDB id on the Sonarr side are skipped and
 * return an empty list.
 *
 * A null mapping (TVDB id exists but Trakt doesn't know that show) is
 * cached the same way, so a fruitless search isn't repeated every
 * refresh cycle.
 *
 * Failed lookups are remembered separately for
 * [TRAKT_ID_LOOKUP_BACKOFF_SECONDS] and surface as failures rather than
 * as "nothing watched" — scoring on missing history would silently
 * mis-prioritise the series.
 *
 * Source value on emitted events: "trakt".
 */
class TraktHistoryProvider(
    private val trakt: TraktClient,
    /** Clock seam — overridden in tests to advance the backoff window. */
    private val now: () -> Instant = { Instant.now() },
    /** Durable id cache. Null keeps the previous memory-only behaviour. */
    private val store: TraktIdStore? = null,
) : WatchHistoryProvider {

    override val name: String = "trakt"
    private val logger = LoggerFactory.getLogger(TraktHistoryProvider::class.java)

    // tvdbId -> trakt_show_id (null means "looked up, not found").
    // Size is bounded by the Sonarr library size (hundreds, not
    // millions), so a plain map is fine — no eviction needed.
    private val idCache = ConcurrentHashMap<Long, OptionalId>()

    // tvdbId -> earliest instant we may retry a failed id lookup.
    // Cleared as soon as a lookup succeeds.
    private val failedLookups = ConcurrentHashMap<Long, Instant>()

    private data class OptionalId(val traktId: Long?)

    override suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>> = runCatching {
        val tvdb = ref.tvdbId ?: return@runCatching emptyList()

        // Memory first, then the durable store — a warm store means a
        // restart costs no Trakt calls at all.
        val cached = idCache[tvdb]
            ?: store?.lookup(tvdb)?.let { OptionalId(it.traktId).also { e -> idCache[tvdb] = e } }
        val traktShowId: Long? = if (cached != null) {
            cached.traktId
        } else {
            // A recent lookup failure parks this series. Without it, a
            // transient upstream error is retried by every sweep for every
            // unresolved series, which keeps the failure alive indefinitely.
            failedLookups[tvdb]?.let { retryAt ->
                if (now().isBefore(retryAt)) {
                    throw TraktLookupBackoffException(tvdb, retryAt)
                }
                failedLookups.remove(tvdb)
            }
            val resolved = try {
                trakt.searchShowByTvdb(tvdb)
            } catch (e: Exception) {
                val retryAt = now().plusSeconds(TRAKT_ID_LOOKUP_BACKOFF_SECONDS)
                failedLookups[tvdb] = retryAt
                logger.info(
                    "trakt search failed for tvdb={} ({}): {} — backing off until {}",
                    tvdb, ref.title, e.message, retryAt,
                )
                throw e
            }
            idCache[tvdb] = OptionalId(resolved)
            store?.save(tvdb, resolved)
            failedLookups.remove(tvdb)
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
