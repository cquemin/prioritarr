package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrStuckFilterTest {
    private val now = Instant.parse("2026-06-14T10:00:00Z")

    private fun cmds() = buildJsonArray {
        addJsonObject { put("id", 1); put("name", "SeriesSearch"); put("status", "started"); put("started", "2026-06-14T09:00:00Z") } // 60m old
        addJsonObject { put("id", 2); put("name", "RssSync"); put("status", "started"); put("started", "2026-06-14T09:55:00Z") }     // 5m old
        addJsonObject { put("id", 3); put("name", "Backup"); put("status", "queued") }                                                 // no started
        addJsonObject { put("id", 4); put("name", "RefreshSeries"); put("status", "completed"); put("started", "2026-06-14T08:00:00Z") } // not started
    }

    @Test fun parses_started_timestamps() {
        val parsed = parseCommands(cmds())
        assertEquals(4, parsed.size)
        assertEquals(Instant.parse("2026-06-14T09:00:00Z"), parsed[0].started)
        assertEquals(null, parsed[2].started)
    }

    @Test fun only_started_commands_older_than_stall_are_stuck() {
        val stuck = stuckCommands(parseCommands(cmds()), now, stallMinutes = 30)
        assertEquals(listOf(1L), stuck.map { it.id })
    }
}
