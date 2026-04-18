package org.yoshiz.app.prioritarr.backend.mapping

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.LoggerFactory
import java.time.Duration

const val REDIS_MAPPING_KEY = "prioritarr:plex_to_series"
val REDIS_MAPPING_TTL: Duration = Duration.ofDays(7)

/**
 * Abstraction over the plex_key → series_id persistent cache. Production
 * uses [LettuceMappingCache]; tests use [InMemoryMappingCache].
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

class LettuceMappingCache(private val redisUrl: String) : MappingCache {
    private val logger = LoggerFactory.getLogger(LettuceMappingCache::class.java)
    private val client: RedisClient = RedisClient.create(redisUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override fun load(): Map<String, Long> =
        try {
            connection.sync().hgetall(REDIS_MAPPING_KEY)
                ?.mapValues { it.value.toLong() }
                ?: emptyMap()
        } catch (e: Exception) {
            logger.warn("Redis: failed to load cached mappings: {}", e.message)
            emptyMap()
        }

    override fun save(mapping: Map<String, Long>) {
        if (mapping.isEmpty()) return
        try {
            val cmds = connection.sync()
            cmds.del(REDIS_MAPPING_KEY)
            cmds.hset(REDIS_MAPPING_KEY, mapping.mapValues { it.value.toString() })
            cmds.expire(REDIS_MAPPING_KEY, REDIS_MAPPING_TTL.toSeconds())
            logger.debug("Redis: saved {} mappings", mapping.size)
        } catch (e: Exception) {
            logger.warn("Redis: failed to save cached mappings: {}", e.message)
        }
    }

    fun close() {
        connection.close()
        client.shutdown()
    }
}
