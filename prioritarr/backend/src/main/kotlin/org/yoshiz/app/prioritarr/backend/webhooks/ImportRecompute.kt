package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.webhooks.import")

/**
 * After a Sonarr import (`Download` event), recompute the series'
 * priority so the UI reflects the new hasFile state immediately rather
 * than waiting for the 30-min refresh-priorities batch.
 *
 * Guards the import-vs-API race: Sonarr's Download event is post-import,
 * but `getEpisodes` may briefly still report hasFile=false. We poll up to
 * [maxRetries] times until every [importedEpisodeIds] entry reports
 * hasFile=true, then recompute. If the retries are exhausted we recompute
 * anyway (best-effort; the next batch corrects any stragglers).
 *
 * Both side effects are injected as lambdas so this is unit-testable
 * without a live Sonarr / DB.
 */
internal suspend fun recomputeAfterImport(
    seriesId: Long,
    importedEpisodeIds: List<Long>,
    fetchEpisodes: suspend (Long) -> JsonArray,
    recompute: suspend (Long) -> Unit,
    maxRetries: Int = 3,
    delayMs: Long = 1500,
) {
    if (importedEpisodeIds.isNotEmpty()) {
        repeat(maxRetries) { attempt ->
            val confirmed = runCatching {
                val byId = fetchEpisodes(seriesId).associate { el ->
                    val o = el.jsonObject
                    (o["id"]?.jsonPrimitive?.longOrNull ?: -1L) to (o["hasFile"]?.jsonPrimitive?.booleanOrNull == true)
                }
                importedEpisodeIds.all { byId[it] == true }
            }.getOrDefault(false)
            if (confirmed) {
                runCatching { recompute(seriesId) }
                    .onFailure { logger.warn("[import-recompute] series {} recompute failed: {}", seriesId, it.message) }
                return
            }
            if (attempt < maxRetries - 1 && delayMs > 0) delay(delayMs)
        }
        logger.info("[import-recompute] series {} hasFile not confirmed after {} tries; recomputing anyway", seriesId, maxRetries)
    }
    runCatching { recompute(seriesId) }
        .onFailure { logger.warn("[import-recompute] series {} recompute failed: {}", seriesId, it.message) }
}
