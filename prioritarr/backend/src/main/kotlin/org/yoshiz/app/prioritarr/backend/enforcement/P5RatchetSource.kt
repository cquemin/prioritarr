package org.yoshiz.app.prioritarr.backend.enforcement

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database

/**
 * Live-editable P5 ratchet configuration. Mirrors [BandwidthSource]'s
 * pattern: read the baseline from config, overlay the DB-persisted
 * override on each call. The reconciler and backfill sweep read [current]
 * every tick so operator changes take effect without a restart.
 */
interface P5RatchetSource {
    fun current(): P5RatchetConfig
    fun save(next: P5RatchetConfig)
    fun reset()
}

class DbP5RatchetSource(
    private val db: Database,
    private val baseline: P5RatchetConfig,
) : P5RatchetSource {
    private val logger = LoggerFactory.getLogger(DbP5RatchetSource::class.java)

    override fun current(): P5RatchetConfig {
        val raw = db.getP5RatchetOverride() ?: return baseline
        return try {
            Json.decodeFromString(P5RatchetConfig.serializer(), raw)
        } catch (e: Exception) {
            logger.warn("p5-ratchet override unparseable: {}; using baseline", e.message)
            baseline
        }
    }

    override fun save(next: P5RatchetConfig) {
        db.setP5RatchetOverride(Json.encodeToString(P5RatchetConfig.serializer(), next))
    }

    override fun reset() { db.clearP5RatchetOverride() }
}
