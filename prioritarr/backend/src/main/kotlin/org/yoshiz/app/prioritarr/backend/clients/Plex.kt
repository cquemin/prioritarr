package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Direct Plex Media Server client for watch-status queries. Used as a
 * fallback when Tautulli history is stale — Plex always knows the
 * current viewCount / lastViewedAt per episode. Mirrors
 * prioritarr/clients/plex.py.
 */
class PlexClient(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    /** Return all library sections as {id, title, type} maps. */
    suspend fun getLibrarySections(): List<Map<String, String?>> {
        val doc = getXml("/library/sections") ?: return emptyList()
        val dirs = doc.getElementsByTagName("Directory")
        return dirs.toList().map {
            mapOf(
                "id" to it.getAttribute("key"),
                "title" to it.getAttribute("title"),
                "type" to it.getAttribute("type"),
            )
        }
    }

    /**
     * Return every show in a library [sectionId] with its external Guid
     * children (`?includeGuids=1`) — one call per section. Each entry:
     * {rating_key, title, guids: List<String>}.
     *
     * This is the authoritative, *live* source of Plex rating-keys for
     * the mapping job. Plex re-indexes change a show's rating-key, and
     * Tautulli's library media-info cache lags behind those changes, so
     * sourcing keys from Plex directly (rather than via Tautulli) keeps
     * the plex_key↔series mapping fresh. The tvdb guid is the stable
     * join key to Sonarr.
     */
    suspend fun getShowsWithGuids(sectionId: String): List<Map<String, Any?>> {
        val doc = getXml("/library/sections/$sectionId/all?includeGuids=1&type=2") ?: return emptyList()
        return doc.getElementsByTagName("Directory").toList().mapNotNull { dir ->
            val ratingKey = dir.getAttribute("ratingKey").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val guids = dir.getElementsByTagName("Guid").toList()
                .map { it.getAttribute("id") }
                .filter { it.isNotEmpty() }
            mapOf(
                "rating_key" to ratingKey,
                "title" to dir.getAttribute("title"),
                "guids" to guids,
            )
        }
    }

    /**
     * Return all episodes for [ratingKey] with watch status.
     * Each entry: {season, episode, watched (bool), last_viewed_at (Long|null), rating_key}
     * The `rating_key` is the per-episode Plex item id, needed to scrobble
     * a single episode back as watched via [markEpisodeWatched].
     */
    suspend fun getShowEpisodesWatchStatus(ratingKey: String): List<Map<String, Any?>> {
        val doc = getXml("/library/metadata/$ratingKey/allLeaves") ?: return emptyList()
        return doc.getElementsByTagName("Video").toList().mapNotNull { v ->
            val season = v.getAttribute("parentIndex").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val episode = v.getAttribute("index").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val episodeKey = v.getAttribute("ratingKey").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val viewCount = v.getAttribute("viewCount").ifEmpty { "0" }.toInt()
            val lastViewedRaw = v.getAttribute("lastViewedAt").ifEmpty { null }
            mapOf(
                "season" to season.toInt(),
                "episode" to episode.toInt(),
                "watched" to (viewCount > 0),
                "last_viewed_at" to lastViewedRaw?.toLong(),
                "rating_key" to episodeKey,
            )
        }
    }

    /**
     * Mark a single Plex item (episode rating-key) as watched. Uses the
     * scrobble endpoint, which bumps viewCount and stamps lastViewedAt
     * server-side — same effect as a user clicking "mark as watched".
     *
     * Plex returns 200 with an empty body on success; we don't parse it.
     * Any HTTP error throws (Ktor default behaviour) and the caller's
     * runCatching turns it into a per-episode failure in the sync report.
     */
    suspend fun markEpisodeWatched(episodeRatingKey: String) {
        http.get("$root/:/scrobble") {
            header("X-Plex-Token", token)
            parameter("identifier", "com.plexapp.plugins.library")
            parameter("key", episodeRatingKey)
        }.bodyAsText()
    }

    /**
     * Number of active playback sessions reported by Plex
     * (`/status/sessions`). The MediaContainer's `size` attribute is the
     * session count; each Video/Track child is one active stream. Returns
     * 0 on error or when nothing is playing. Used to pause background
     * Tdarr transcoding while anything is streaming.
     */
    suspend fun activeSessionCount(): Int {
        val doc = getXml("/status/sessions") ?: return 0
        doc.getAttribute("size").toIntOrNull()?.let { return it }
        return doc.getElementsByTagName("Video").length + doc.getElementsByTagName("Track").length
    }

    /**
     * Like [activeSessionCount], but returns **null** when the probe
     * itself failed, rather than collapsing that into 0.
     *
     * The subtitle ladder needs the distinction: 0 means "safe to burn
     * CPU", whereas a failed probe means "unknown", and unknown must
     * behave like busy.
     */
    suspend fun activeSessionCountOrNull(): Int? {
        val doc = try {
            getXml("/status/sessions") ?: return null
        } catch (_: Exception) {
            return null
        }
        doc.getAttribute("size").toIntOrNull()?.let { return it }
        return doc.getElementsByTagName("Video").length + doc.getElementsByTagName("Track").length
    }

    private suspend fun getXml(path: String): Element? {
        val body: String = http.get("$root$path") {
            header("X-Plex-Token", token)
        }.bodyAsText()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
        return doc.documentElement
    }
}

private fun NodeList.toList(): List<Element> =
    (0 until length).mapNotNull { item(it) as? Element }
