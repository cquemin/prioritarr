package org.yoshiz.app.prioritarr.backend.enforcement

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.config.BandwidthSettings
import org.yoshiz.app.prioritarr.backend.database.Database

/**
 * Live-editable bandwidth settings. Mirrors [ThresholdsSource]'s
 * pattern: read the baseline from config, overlay the DB-persisted
 * override on each call. The reconciler reads [current] every tick
 * so toggling quiet mode or updating the line cap takes effect on
 * the next scheduled reconcile (no restart).
 */
interface BandwidthSource {
    fun current(): BandwidthSettings
    fun save(next: BandwidthSettings)
    fun reset()
}

class DbBandwidthSource(
    private val db: Database,
    private val baseline: BandwidthSettings,
) : BandwidthSource {
    private val logger = LoggerFactory.getLogger(DbBandwidthSource::class.java)

    override fun current(): BandwidthSettings {
        val raw = db.getBandwidthOverride() ?: return baseline
        return try {
            Json.decodeFromString(BandwidthSettings.serializer(), raw)
        } catch (e: Exception) {
            logger.warn("bandwidth override unparseable: {}; using baseline", e.message)
            baseline
        }
    }

    override fun save(next: BandwidthSettings) {
        db.setBandwidthOverride(Json.encodeToString(BandwidthSettings.serializer(), next))
    }

    override fun reset() { db.clearBandwidthOverride() }
}
