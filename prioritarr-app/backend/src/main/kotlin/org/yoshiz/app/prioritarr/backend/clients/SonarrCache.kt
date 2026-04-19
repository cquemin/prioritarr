package org.yoshiz.app.prioritarr.backend.clients

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import java.time.Duration
import java.time.Instant

/**
 * TTL cache around [SonarrClient.getAllSeries] — the upstream call takes
 * 6–10s at ~450 series (1.8 MB payload), which makes every UI list
 * endpoint visibly slow. Cached for [ttl]; invalidated by any caller
 * via [invalidate] (e.g. after a series add/remove event).
 */
class SonarrCache(
    private val sonarr: SonarrClient,
    private val ttl: Duration = Duration.ofMinutes(5),
) {
    private val mutex = Mutex()
    @Volatile private var cached: JsonArray? = null
    @Volatile private var cachedAt: Instant = Instant.EPOCH

    suspend fun getAllSeries(): JsonArray {
        cached?.let { if (Duration.between(cachedAt, Instant.now()) < ttl) return it }
        return mutex.withLock {
            cached?.let { if (Duration.between(cachedAt, Instant.now()) < ttl) return@withLock it }
            val fresh = sonarr.getAllSeries()
            cached = fresh
            cachedAt = Instant.now()
            fresh
        }
    }

    fun invalidate() {
        cached = null
    }
}
