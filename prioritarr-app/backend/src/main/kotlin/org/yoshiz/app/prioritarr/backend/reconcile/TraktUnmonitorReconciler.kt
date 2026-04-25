package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TraktClient
import org.yoshiz.app.prioritarr.backend.config.TraktUnmonitorSettings
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.schemas.EpisodeRef

/**
 * Trakt→Sonarr unmonitor reconciler.
 *
 * For each Sonarr series: fetches Trakt's watched-episode set, then
 * unmonitors every Sonarr episode that matches `(season, number)` AND
 * has `hasFile=false` AND `monitored=true` AND has aired.
 *
 * Never touches episodes with files — that's WatchedArchiver's domain.
 * Never touches episodes flagged as specials when skip_specials=true.
 *
 * Per-series opt-out: if the Sonarr series has a tag whose label
 * matches `settings.protectTag`, the whole series is skipped. The tag
 * is auto-created on first run so users can add it from Sonarr's UI
 * before prioritarr ever sees it.
 *
 * Dry-run returns the full planned diff without writing.
 */
class TraktUnmonitorReconciler(
    private val sonarr: SonarrClient,
    private val trakt: TraktClient?,
    private val db: Database,
) {
    private val logger = LoggerFactory.getLogger(TraktUnmonitorReconciler::class.java)

    suspend fun reconcileAll(settings: TraktUnmonitorSettings, dryRun: Boolean, limit: Int? = null): LibraryUnmonitorReport {
        if (trakt == null) {
            logger.info("trakt-unmonitor: trakt not configured, skipping")
            return LibraryUnmonitorReport(dryRun = dryRun, totalSeries = 0, unmonitoredTotal = 0, perSeries = emptyList())
        }
        val allSeries = try {
            sonarr.getAllSeries()
        } catch (e: Exception) {
            logger.warn("trakt-unmonitor: sonarr fetch failed: {}", e.message)
            return LibraryUnmonitorReport(dryRun = dryRun, totalSeries = 0, unmonitoredTotal = 0, perSeries = emptyList())
        }

        // Resolve protect-tag id once per run — getOrCreateTag is
        // idempotent and cheap, so we don't need to memo across runs.
        val protectTagId = runCatching { sonarr.getOrCreateTag(settings.protectTag) }.getOrElse {
            logger.warn("trakt-unmonitor: could not resolve/create tag '{}': {}", settings.protectTag, it.message)
            null
        }

        val reports = mutableListOf<SeriesUnmonitorReport>()
        var total = 0
        val targets = if (limit != null && limit > 0) allSeries.take(limit) else allSeries
        for (seriesEl in targets) {
            val seriesObj = seriesEl.jsonObject
            val seriesId = seriesObj["id"]?.jsonPrimitive?.longOrNull ?: continue
            val r = reconcileOneSeries(seriesObj, settings, protectTagId, dryRun)
            reports += r
            total += r.unmonitored.size
        }
        logger.info("trakt-unmonitor: series={} unmonitored={} dryRun={}", reports.size, total, dryRun)
        return LibraryUnmonitorReport(dryRun = dryRun, totalSeries = reports.size, unmonitoredTotal = total, perSeries = reports)
    }

    suspend fun reconcileSeries(seriesId: Long, settings: TraktUnmonitorSettings, dryRun: Boolean): SeriesUnmonitorReport {
        if (trakt == null) {
            return SeriesUnmonitorReport(seriesId = seriesId, title = "(unknown)", unmonitored = emptyList(), skippedReason = "trakt not configured", errors = emptyList())
        }
        val seriesObj = try {
            sonarr.getSeries(seriesId)
        } catch (e: Exception) {
            return SeriesUnmonitorReport(seriesId, "(unknown)", emptyList(), "sonarr lookup failed: ${e.message}", emptyList())
        }
        val protectTagId = runCatching { sonarr.getOrCreateTag(settings.protectTag) }.getOrNull()
        return reconcileOneSeries(seriesObj, settings, protectTagId, dryRun)
    }

    private suspend fun reconcileOneSeries(
        seriesObj: JsonObject,
        settings: TraktUnmonitorSettings,
        protectTagId: Int?,
        dryRun: Boolean,
    ): SeriesUnmonitorReport {
        val seriesId = seriesObj["id"]?.jsonPrimitive?.longOrNull
            ?: return SeriesUnmonitorReport(0, "(no id)", emptyList(), "series missing id", emptyList())
        val title = seriesObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tvdbId = seriesObj["tvdbId"]?.jsonPrimitive?.longOrNull
        val seriesMonitored = seriesObj["monitored"]?.jsonPrimitive?.booleanOrNull == true
        val tagIds: List<Int> = seriesObj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull?.toInt() } ?: emptyList()

        if (!seriesMonitored) {
            return SeriesUnmonitorReport(seriesId, title, emptyList(), "series not monitored", emptyList())
        }
        if (protectTagId != null && protectTagId in tagIds) {
            return SeriesUnmonitorReport(seriesId, title, emptyList(), "protected by tag '${settings.protectTag}'", emptyList())
        }
        if (tvdbId == null) {
            return SeriesUnmonitorReport(seriesId, title, emptyList(), "no tvdbId", emptyList())
        }

        val errors = mutableListOf<String>()

        // Fetch Trakt watched set. An empty result (no views) means the
        // user has watched nothing on Trakt for this series — correct
        // behaviour: we skip without unmonitoring anything.
        val traktWatched: Set<Pair<Int, Int>> = try {
            val showId = trakt!!.searchShowByTvdb(tvdbId) ?: return SeriesUnmonitorReport(seriesId, title, emptyList(), "trakt: no show for tvdbId=$tvdbId", emptyList())
            trakt.getShowHistory(showId, limit = 1000).mapNotNull { el ->
                val obj = el.jsonObject
                val ep = obj["episode"] as? JsonObject ?: return@mapNotNull null
                val s = ep["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                val e = ep["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                s to e
            }.toSet()
        } catch (e: Exception) {
            errors += "trakt fetch failed: ${e.message}"
            return SeriesUnmonitorReport(seriesId, title, emptyList(), null, errors)
        }
        if (traktWatched.isEmpty()) {
            return SeriesUnmonitorReport(seriesId, title, emptyList(), "no trakt history", errors)
        }

        // Cross-reference with Sonarr's episode list.
        val sonarrEpisodes = try { sonarr.getEpisodes(seriesId) } catch (e: Exception) {
            errors += "sonarr episodes fetch failed: ${e.message}"
            return SeriesUnmonitorReport(seriesId, title, emptyList(), null, errors)
        }

        val toUnmonitor = mutableListOf<Pair<Long, EpisodeRef>>()
        for (epEl in sonarrEpisodes) {
            val ep = epEl.jsonObject
            val epId = ep["id"]?.jsonPrimitive?.longOrNull ?: continue
            val season = ep["seasonNumber"]?.jsonPrimitive?.longOrNull?.toInt() ?: continue
            val number = ep["episodeNumber"]?.jsonPrimitive?.longOrNull?.toInt() ?: continue
            val hasFile = ep["hasFile"]?.jsonPrimitive?.booleanOrNull == true
            val monitored = ep["monitored"]?.jsonPrimitive?.booleanOrNull == true
            // Sonarr v4 returns hasAired=null for episodes outside its
            // "near-now" window — using it directly would treat aired
            // episodes as not-yet-aired. Fall back to airDateUtc < now
            // when the flag isn't a clean boolean. Episodes with no air
            // date at all (rare; placeholder rows) stay safe by being
            // treated as not-aired.
            val hasAired = ep["hasAired"]?.jsonPrimitive?.booleanOrNull
                ?: ep["airDateUtc"]?.jsonPrimitive?.contentOrNull?.let { iso ->
                    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().isBefore(java.time.Instant.now()) }
                        .getOrDefault(false)
                } ?: false
            if (hasFile) continue                       // never unmonitor episodes we already have
            if (!monitored) continue                    // already the target state
            if (!hasAired) continue                     // don't touch future episodes
            if (settings.skipSpecials && season == 0) continue
            if ((season to number) !in traktWatched) continue
            toUnmonitor += epId to EpisodeRef(season = season, number = number)
        }

        if (toUnmonitor.isEmpty()) {
            return SeriesUnmonitorReport(seriesId, title, emptyList(), null, errors)
        }

        if (!dryRun) {
            try {
                sonarr.setEpisodesMonitored(toUnmonitor.map { it.first }, monitored = false)
                try {
                    db.appendAudit(
                        action = "trakt_unmonitored",
                        seriesId = seriesId,
                        client = null,
                        clientId = null,
                        details = kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"count":${toUnmonitor.size},"episodes":${toUnmonitor.joinToString(",", "[", "]") { "\"S%02dE%02d\"".format(it.second.season, it.second.number) }}}""",
                        ),
                    )
                } catch (_: Exception) { /* audit best-effort */ }
            } catch (e: Exception) {
                errors += "sonarr bulk monitor failed: ${e.message}"
                return SeriesUnmonitorReport(seriesId, title, emptyList(), null, errors)
            }
        }

        logger.info(
            "trakt-unmonitor: series='{}' unmonitored={} dryRun={}",
            title, toUnmonitor.size, dryRun,
        )
        return SeriesUnmonitorReport(
            seriesId = seriesId,
            title = title,
            unmonitored = toUnmonitor.map { it.second },
            skippedReason = null,
            errors = errors,
        )
    }
}

@Serializable
data class SeriesUnmonitorReport(
    val seriesId: Long,
    val title: String,
    val unmonitored: List<EpisodeRef>,
    val skippedReason: String?,
    val errors: List<String>,
)

@Serializable
data class LibraryUnmonitorReport(
    val dryRun: Boolean,
    val totalSeries: Int,
    val unmonitoredTotal: Int,
    val perSeries: List<SeriesUnmonitorReport>,
)
