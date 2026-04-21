package org.yoshiz.app.prioritarr.backend.mapping

/**
 * Helper namespace — seeds a [MappingState] from a plex_key → series_id
 * map. Used on startup to re-hydrate from the persistent mapping cache
 * (SQLite) before the first full refresh completes.
 */
object Hydrate {
    fun seed(plexToSid: Map<String, Long>): Triple<Map<Long, Long>, Map<String, String>, Map<String, Long>> =
        Triple(emptyMap(), emptyMap(), plexToSid)
}

/** Apply the triple returned by Hydrate.seed() to [state]. */
internal fun MappingState.hydrate(
    triple: Triple<Map<Long, Long>, Map<String, String>, Map<String, Long>>,
) {
    apply(triple.first, triple.second, triple.third, tautulliUp = true)
}
