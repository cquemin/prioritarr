package org.yoshiz.app.prioritarr.backend.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.slf4j.LoggerFactory

/** Holds the three in-memory mapping tables used across the app. */
class MappingState {
    @Volatile var tvdbToSeries: Map<Long, Long> = emptyMap()
        private set
    @Volatile var titleToPlexKey: Map<String, String> = emptyMap()
        private set
    @Volatile var plexKeyToSeriesId: Map<String, Long> = emptyMap()
        private set
    @Volatile var tautulliAvailable: Boolean = true
        private set
    @Volatile var lastRefreshStats: RefreshStats? = null
        internal set
    @Volatile var lastRefreshAt: String? = null
        internal set

    fun plexKeyForSeriesTitle(title: String): String? =
        titleToPlexKey[normaliseTitle(title)]

    fun seriesIdForPlexKey(plexKey: String): Long? = plexKeyToSeriesId[plexKey]

    internal fun apply(
        tvdb: Map<Long, Long>,
        title: Map<String, String>,
        keyToSid: Map<String, Long>,
        tautulliUp: Boolean,
    ) {
        tvdbToSeries = tvdb
        titleToPlexKey = title
        plexKeyToSeriesId = keyToSid
        tautulliAvailable = tautulliUp
    }

    /** Test-only helper: wipe all three in-memory tables. */
    fun clear() = apply(emptyMap(), emptyMap(), emptyMap(), tautulliUp = true)

    /** Test-only helper: insert a plex_key → series_id entry. */
    @Synchronized
    fun inject(plexKey: String, seriesId: Long) {
        plexKeyToSeriesId = plexKeyToSeriesId + (plexKey to seriesId)
    }
}

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.mapping")

internal fun normaliseTitle(title: String): String = title.trim().lowercase()

internal fun extractFolderName(path: String): String =
    path.trimEnd('/', '\\').let {
        it.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
    }

private val TVDB_REGEX = Regex("""tvdb://(\d+)""")
private val THETVDB_REGEX = Regex("""thetvdb://(\d+)""")

internal fun extractTvdbFromGuids(guids: List<String>): Long? {
    for (g in guids) {
        TVDB_REGEX.find(g)?.let { return it.groupValues[1].toLong() }
        THETVDB_REGEX.find(g)?.let { return it.groupValues[1].toLong() }
    }
    return null
}

@kotlinx.serialization.Serializable
data class RefreshStats(
    val cached: Int = 0,
    val tvdb: Int = 0,
    val path: Int = 0,
    val title: Int = 0,
    val unmatched: Int = 0,
)

/**
 * Rebuild plex↔sonarr mapping tables. Mirrors
 * prioritarr/main.py::_refresh_mappings including the 3-step matching
 * (TVDB → path → title) and Redis cache skip-on-hit optimisation.
 */
suspend fun refreshMappings(
    sonarr: SonarrClient,
    tautulli: TautulliClient,
    cache: MappingCache,
    state: MappingState,
): RefreshStats {
    val allSeries: JsonArray = try {
        sonarr.getAllSeries()
    } catch (e: Exception) {
        logger.error("refreshMappings: failed to fetch Sonarr series", e)
        return RefreshStats()
    }

    val newTvdb = mutableMapOf<Long, Long>()
    val sonarrTitles = mutableMapOf<String, Long>()
    val sonarrFolders = mutableMapOf<String, Long>()

    for (el in allSeries) {
        val s = el.jsonObject
        val sid = s["id"]?.jsonPrimitive?.longOrNull ?: continue
        s["tvdbId"]?.jsonPrimitive?.longOrNull?.let { newTvdb[it] = sid }
        val title = s["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        sonarrTitles[normaliseTitle(title)] = sid
        val path = s["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isNotEmpty()) sonarrFolders[extractFolderName(path)] = sid
    }

    val libraries: JsonArray = try {
        tautulli.getShowLibraries()
    } catch (e: Exception) {
        logger.error("refreshMappings: failed to fetch Tautulli libraries", e)
        state.apply(newTvdb, emptyMap(), emptyMap(), tautulliUp = false)
        return RefreshStats()
    }

    val cached = cache.load()
    val newTitleToKey = mutableMapOf<String, String>()
    val newKeyToSid = mutableMapOf<String, Long>()
    var stats = RefreshStats()

    for (libEl in libraries) {
        val lib = libEl.jsonObject
        val sectionId = lib["section_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
        val items = try {
            tautulli.getLibraryMediaInfo(sectionId)
        } catch (e: Exception) {
            logger.error("refreshMappings: failed to get media for section $sectionId", e)
            continue
        }

        for (itemEl in items) {
            val item = itemEl.jsonObject
            val plexKey = item["rating_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val plexTitle = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (plexKey.isEmpty()) continue

            val norm = normaliseTitle(plexTitle)
            newTitleToKey[norm] = plexKey

            cached[plexKey]?.let { sid ->
                if (newTvdb.values.contains(sid) || sonarrTitles.values.contains(sid)) {
                    newKeyToSid[plexKey] = sid
                    stats = stats.copy(cached = stats.cached + 1)
                    return@let
                }
            }
            if (newKeyToSid.containsKey(plexKey)) continue

            var matched = false
            try {
                val metadata = tautulli.getMetadata(plexKey).jsonObject
                val guids = (metadata["guids"] as? JsonArray).orEmpty()
                    .map { it.jsonPrimitive.contentOrNull.orEmpty() }
                extractTvdbFromGuids(guids)?.let { tvdbId ->
                    newTvdb[tvdbId]?.let { sid ->
                        newKeyToSid[plexKey] = sid
                        stats = stats.copy(tvdb = stats.tvdb + 1)
                        matched = true
                    }
                }

                if (!matched) {
                    val mediaInfo = (metadata["media_info"] as? JsonArray).orEmpty()
                    outer@ for (mi in mediaInfo) {
                        val parts = (mi.jsonObject["parts"] as? JsonArray).orEmpty()
                        for (part in parts) {
                            val file = part.jsonObject["file"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (file.isEmpty()) continue
                            val segments = file.replace("\\", "/").split("/")
                            for ((i, p) in segments.withIndex()) {
                                if (p.lowercase().startsWith("season") && i > 0) {
                                    val folder = segments[i - 1].trim().lowercase()
                                    sonarrFolders[folder]?.let { sid ->
                                        newKeyToSid[plexKey] = sid
                                        stats = stats.copy(path = stats.path + 1)
                                        matched = true
                                    }
                                    break
                                }
                            }
                            if (matched) break@outer
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("refreshMappings: get_metadata failed for $plexKey ($plexTitle)")
            }

            if (!matched) {
                sonarrTitles[norm]?.let { sid ->
                    newKeyToSid[plexKey] = sid
                    stats = stats.copy(title = stats.title + 1)
                } ?: run {
                    stats = stats.copy(unmatched = stats.unmatched + 1)
                }
            }
        }
    }

    state.apply(newTvdb, newTitleToKey, newKeyToSid, tautulliUp = true)
    state.lastRefreshStats = stats
    state.lastRefreshAt = org.yoshiz.app.prioritarr.backend.database.Database.nowIsoOffset()
    cache.save(newKeyToSid)

    logger.info(
        "refreshMappings: {} sonarr series, {} plex shows, {} matched " +
            "(cached={}, tvdb={}, path={}, title={}, unmatched={})",
        allSeries.size, newTitleToKey.size, newKeyToSid.size,
        stats.cached, stats.tvdb, stats.path, stats.title, stats.unmatched,
    )
    return stats
}

/** Handle `val x = arr as? JsonArray; x.orEmpty()` on a nullable JsonArray. */
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
