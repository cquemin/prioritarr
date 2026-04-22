package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import org.yoshiz.app.prioritarr.backend.database.Database

/**
 * Live-mutable thresholds. Callers invoke [current] before each
 * priority compute so a Settings-page PUT is visible immediately
 * without restarting the app.
 *
 * Lookup order:
 *   1. DB override (stored as a JSON blob that may be a partial patch)
 *   2. Baseline from env / config.yaml (passed in at startup)
 *
 * The DB blob is interpreted as a patch — fields absent in the blob
 * fall back to the baseline. That matches the UI model where the user
 * tweaks a couple of knobs and leaves the rest alone.
 */
interface ThresholdsSource {
    fun current(): PriorityThresholds

    /** Replace the persisted override with [next]. */
    fun save(next: PriorityThresholds)

    /** Drop the override so [current] returns the baseline. */
    fun reset()
}

/**
 * Default implementation backed by [Database].
 *
 * Nothing is cached in memory — the DB read is a single-row SELECT on
 * an indexed primary key (O(microseconds)), and the priority cache
 * already absorbs any load that isn't strictly bottlenecked on this.
 */
class DbThresholdsSource(
    private val db: Database,
    private val baseline: PriorityThresholds,
) : ThresholdsSource {
    private val logger = LoggerFactory.getLogger(DbThresholdsSource::class.java)

    override fun current(): PriorityThresholds {
        val raw = db.getThresholdsOverride() ?: return baseline
        return try {
            val patch = Json.parseToJsonElement(raw).jsonObject
            applyPatch(baseline, patch)
        } catch (e: Exception) {
            logger.warn("thresholds override payload unparseable: {}; falling back to baseline", e.message)
            baseline
        }
    }

    override fun save(next: PriorityThresholds) {
        db.setThresholdsOverride(Json.encodeToString(PriorityThresholds.serializer(), next))
    }

    override fun reset() {
        db.clearThresholdsOverride()
    }
}

/**
 * Overlay a JSON patch onto [base]. Unknown keys are ignored so a DB
 * row written with extra fields (e.g. after a downgrade) doesn't
 * crash; missing keys fall back to [base].
 *
 * Public (not private) because the preview endpoint reuses it to build
 * a one-off snapshot of what the rules would return under proposed
 * thresholds without writing them to disk.
 */
fun applyPatch(base: PriorityThresholds, patch: JsonObject): PriorityThresholds {
    fun d(key: String, fallback: Double) =
        patch[key]?.jsonPrimitive?.doubleOrNull ?: fallback
    fun i(key: String, fallback: Int) =
        patch[key]?.jsonPrimitive?.intOrNull ?: fallback
    return PriorityThresholds(
        p1WatchPctMin = d("p1WatchPctMin", base.p1WatchPctMin),
        p1DaysSinceWatchMax = i("p1DaysSinceWatchMax", base.p1DaysSinceWatchMax),
        p1DaysSinceReleaseMax = i("p1DaysSinceReleaseMax", base.p1DaysSinceReleaseMax),
        p1HiatusGapDays = i("p1HiatusGapDays", base.p1HiatusGapDays),
        p1HiatusReleaseWindowDays = i("p1HiatusReleaseWindowDays", base.p1HiatusReleaseWindowDays),
        p2WatchPctMin = d("p2WatchPctMin", base.p2WatchPctMin),
        p2DaysSinceWatchMax = i("p2DaysSinceWatchMax", base.p2DaysSinceWatchMax),
        p3WatchPctMin = d("p3WatchPctMin", base.p3WatchPctMin),
        p3UnwatchedMax = i("p3UnwatchedMax", base.p3UnwatchedMax),
        p3DaysSinceWatchMax = i("p3DaysSinceWatchMax", base.p3DaysSinceWatchMax),
        p4MinWatched = i("p4MinWatched", base.p4MinWatched),
    )
}
