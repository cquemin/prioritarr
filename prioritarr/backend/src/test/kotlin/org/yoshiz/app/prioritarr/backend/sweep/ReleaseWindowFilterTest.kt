package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseWindowFilterTest {
    // 2024-01-10T00:00:00Z = 1704844800
    private val now = 1704844800L
    private fun rec(id: Long, air: String): JsonObject = buildJsonObject {
        put("seriesId", JsonPrimitive(1))
        put("id", JsonPrimitive(id))
        put("airDateUtc", JsonPrimitive(air))
    }
    private fun ids(a: JsonArray) = a.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.longOrNull }

    @Test fun keeps_only_episodes_inside_1h_to_48h_window() {
        val recs = buildJsonArray {
            add(rec(1L, "2024-01-09T23:30:00Z")) // 30 min old  -> too fresh, drop
            add(rec(2L, "2024-01-09T22:00:00Z")) // 2 h old     -> keep
            add(rec(3L, "2024-01-08T00:00:00Z")) // 48 h old    -> keep (boundary)
            add(rec(4L, "2024-01-07T00:00:00Z")) // 72 h old    -> drop
        }
        val out = filterRecordsByReleaseWindow(recs, now, minAgeMinutes = 60, maxAgeHours = 48)
        assertEquals(listOf(2L, 3L), ids(out))
    }

    @Test fun drops_rows_with_missing_or_unparseable_air_date() {
        val recs = buildJsonArray {
            add(buildJsonObject { put("seriesId", JsonPrimitive(1)); put("id", JsonPrimitive(9)) })
            add(rec(2L, "not-a-date"))
        }
        val out = filterRecordsByReleaseWindow(recs, now, minAgeMinutes = 60, maxAgeHours = 48)
        assertEquals(emptyList(), ids(out))
    }
}
