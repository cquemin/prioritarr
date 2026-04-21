package org.yoshiz.app.prioritarr.backend.mapping

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.database.Database

/**
 * Abstraction over the plex_key → series_id persistent cache. Production
 * uses [SqliteMappingCache] (same SQLite file the rest of the app writes
 * to); tests use [InMemoryMappingCache].
 *
 * Was previously Redis-backed via Lettuce. The access pattern (batch
 * load on boot + hourly full rewrite) fits SQLite better than Redis:
 *   - no extra infra dependency
 *   - data survives redis-m restarts
 *   - the Authelia sessions (unrelated) remain the only reason to keep
 *     Redis in the stack.
 */
interface MappingCache {
    fun load(): Map<String, Long>
    fun save(mapping: Map<String, Long>)
}

class InMemoryMappingCache : MappingCache {
    private val store = mutableMapOf<String, Long>()
    override fun load(): Map<String, Long> = store.toMap()
    override fun save(mapping: Map<String, Long>) {
        store.clear()
        store.putAll(mapping)
    }
}

/**
 * SQLite-backed mapping cache. `save` wipes and reinserts atomically in
 * a single transaction so readers never observe a partially-rewritten
 * map. `load` reads the whole table — cheap at the row counts we care
 * about (~500 shows).
 */
class SqliteMappingCache(private val db: Database) : MappingCache {
    private val logger = LoggerFactory.getLogger(SqliteMappingCache::class.java)

    override fun load(): Map<String, Long> =
        try {
            db.q.listMappingCache().executeAsList()
                .associate { it.plex_key to it.series_id }
        } catch (e: Exception) {
            logger.warn("sqlite: failed to load cached mappings: {}", e.message)
            emptyMap()
        }

    override fun save(mapping: Map<String, Long>) {
        if (mapping.isEmpty()) return
        try {
            val now = Database.nowIsoOffset()
            db.q.transaction {
                db.q.deleteAllMappingCache()
                for ((key, sid) in mapping) {
                    db.q.upsertMappingCache(plex_key = key, series_id = sid, updated_at = now)
                }
            }
            logger.debug("sqlite: saved {} mappings", mapping.size)
        } catch (e: Exception) {
            logger.warn("sqlite: failed to save cached mappings: {}", e.message)
        }
    }
}
