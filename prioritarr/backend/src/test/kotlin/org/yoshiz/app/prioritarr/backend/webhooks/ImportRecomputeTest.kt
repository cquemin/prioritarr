package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportRecomputeTest {
    private fun episodes(vararg pairs: Pair<Long, Boolean>): JsonArray = buildJsonArray {
        pairs.forEach { (id, hasFile) ->
            add(buildJsonObject { put("id", JsonPrimitive(id)); put("hasFile", JsonPrimitive(hasFile)) })
        }
    }

    @Test fun recomputes_once_hasfile_is_true() = runTest {
        var recomputed = 0
        recomputeAfterImport(
            seriesId = 7L,
            importedEpisodeIds = listOf(100L),
            fetchEpisodes = { episodes(100L to true) },
            recompute = { recomputed++ },
            maxRetries = 3, delayMs = 0,
        )
        assertEquals(1, recomputed)
    }

    @Test fun retries_until_hasfile_flips_then_recomputes() = runTest {
        var calls = 0
        var recomputed = 0
        recomputeAfterImport(
            seriesId = 7L,
            importedEpisodeIds = listOf(100L),
            fetchEpisodes = {
                calls++
                if (calls < 2) episodes(100L to false) else episodes(100L to true)
            },
            recompute = { recomputed++ },
            maxRetries = 3, delayMs = 0,
        )
        assertEquals(2, calls)
        assertEquals(1, recomputed)
    }

    @Test fun recomputes_anyway_after_exhausting_retries() = runTest {
        var recomputed = 0
        recomputeAfterImport(
            seriesId = 7L,
            importedEpisodeIds = listOf(100L),
            fetchEpisodes = { episodes(100L to false) },
            recompute = { recomputed++ },
            maxRetries = 2, delayMs = 0,
        )
        assertEquals(1, recomputed)
    }

    @Test fun empty_episode_list_recomputes_immediately() = runTest {
        var fetches = 0; var recomputed = 0
        recomputeAfterImport(
            seriesId = 7L,
            importedEpisodeIds = emptyList(),
            fetchEpisodes = { fetches++; episodes() },
            recompute = { recomputed++ },
            maxRetries = 3, delayMs = 0,
        )
        assertEquals(0, fetches)
        assertEquals(1, recomputed)
    }
}
