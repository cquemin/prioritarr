package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** A Sonarr command as reported by GET /api/v3/command. */
data class SonarrCommand(
    val id: Long,
    val name: String,
    val status: String,
    val started: Instant?,
)

/** Parse the raw /api/v3/command array. Missing/blank/unparseable `started` -> null. */
fun parseCommands(arr: JsonArray): List<SonarrCommand> = arr.mapNotNull { el ->
    val o = el.jsonObject
    val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: ""
    val status = o["status"]?.jsonPrimitive?.contentOrNull ?: ""
    val started = o["started"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    SonarrCommand(id, name, status, started)
}

/** Commands that are actively running and have been for longer than [stallMinutes]. */
fun stuckCommands(commands: List<SonarrCommand>, now: Instant, stallMinutes: Int): List<SonarrCommand> =
    commands.filter { c ->
        c.status == "started" && c.started != null &&
            now.isAfter(c.started.plusSeconds(stallMinutes * 60L))
    }
