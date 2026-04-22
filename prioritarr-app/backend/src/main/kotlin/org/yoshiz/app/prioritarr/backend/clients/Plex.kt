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
