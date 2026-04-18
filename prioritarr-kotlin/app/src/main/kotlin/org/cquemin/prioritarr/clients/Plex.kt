package org.cquemin.prioritarr.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
     * Each entry: {season, episode, watched (bool), last_viewed_at (Long|null)}
     */
    suspend fun getShowEpisodesWatchStatus(ratingKey: String): List<Map<String, Any?>> {
        val doc = getXml("/library/metadata/$ratingKey/allLeaves") ?: return emptyList()
        return doc.getElementsByTagName("Video").toList().mapNotNull { v ->
            val season = v.getAttribute("parentIndex").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val episode = v.getAttribute("index").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val viewCount = v.getAttribute("viewCount").ifEmpty { "0" }.toInt()
            val lastViewedRaw = v.getAttribute("lastViewedAt").ifEmpty { null }
            mapOf(
                "season" to season.toInt(),
                "episode" to episode.toInt(),
                "watched" to (viewCount > 0),
                "last_viewed_at" to lastViewedRaw?.toLong(),
            )
        }
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
