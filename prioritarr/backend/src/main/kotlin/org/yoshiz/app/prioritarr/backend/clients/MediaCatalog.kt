package org.yoshiz.app.prioritarr.backend.clients

/**
 * Media item — the thing prioritarr orders downloads for.
 *
 * Sealed hierarchy so future additions (Radarr movies, Lidarr albums,
 * Readarr books) stay type-safe. Priority compute, snapshots, routes,
 * and DB tables all branch on the concrete variant. Shared fields
 * (id, title) live on the base type; discriminator ids vary per
 * tracker (TVDB for series, TMDB/IMDB for movies, MusicBrainz for
 * albums, etc.).
 */
sealed class MediaItem {
    abstract val id: Long
    abstract val title: String

    /** Sonarr series — the only variant implemented today. */
    data class Series(
        override val id: Long,
        override val title: String,
        val tvdbId: Long?,
    ) : MediaItem()

    /** Radarr movie — scaffolding for a future implementation. */
    data class Movie(
        override val id: Long,
        override val title: String,
        val tmdbId: Long?,
        val imdbId: String?,
    ) : MediaItem()
}

/**
 * Tracker-agnostic catalog interface. Abstracts over Sonarr/Radarr/
 * Lidarr/Readarr so the series-centric rest of the app can be
 * generalised one subsystem at a time.
 *
 * Currently only [SonarrCatalog] implements this, and the rest of
 * the codebase still calls [SonarrClient] concretely. Adding Radarr
 * means:
 *
 *   1. Implement `MediaCatalog<MediaItem.Movie>` around a new
 *      RadarrClient (HTTP surface is similar: list, get-by-id,
 *      trigger-search, manage queue).
 *   2. Duplicate the series-specific pipeline for movies:
 *        - MovieSnapshot (watched: Boolean, releaseDate, missing: Boolean)
 *        - computePriorityMovie — different rule set since movies are
 *          single-item, not multi-episode (P1 "not watched, release
 *          within N days", P2 "watched sequel exists", P5 "watched
 *          or never-available", etc.)
 *        - movie_priority_cache DB table (mirror of series_priority_cache)
 *        - managed_movie_downloads DB table (mirror of managed_downloads)
 *        - /api/v2/movies route namespace
 *   3. Wire the webhook: Radarr's "On Grab" → a new POST endpoint
 *      that classifies + reorders the download's priority.
 *   4. UI: parallel MoviesPage or a unified list with a media-type
 *      switcher.
 *
 * The bulk download actions + DownloadClient registry are already
 * media-type agnostic — adding movies does NOT require touching the
 * downloader layer.
 */
interface MediaCatalog<T : MediaItem> {
    /** Stable name used on DB discriminators + UI chips (e.g. "sonarr", "radarr"). */
    val catalogName: String

    /** List every item the tracker knows about. Cached by the series/movie-cache job. */
    suspend fun listAll(): List<T>

    /** Fetch a single item by its tracker-side id. */
    suspend fun get(id: Long): T?
}
