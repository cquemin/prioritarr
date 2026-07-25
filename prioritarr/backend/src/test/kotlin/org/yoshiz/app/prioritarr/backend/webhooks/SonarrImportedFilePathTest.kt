package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SonarrImportedFilePathTest {

    private fun obj(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test fun prefers_full_episodeFile_path() {
        val payload = obj(
            """
            {
              "eventType": "Download",
              "series": { "path": "/storage/media/video/anime/Show" },
              "episodeFile": {
                "path": "/storage/media/video/anime/Show/Season 01/Show.S01E01.mkv",
                "relativePath": "Season 01/Show.S01E01.mkv"
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            "/storage/media/video/anime/Show/Season 01/Show.S01E01.mkv",
            sonarrImportedFilePath(payload),
        )
    }

    @Test fun joins_series_path_and_relativePath_when_no_full_path() {
        val payload = obj(
            """
            {
              "eventType": "Download",
              "series": { "path": "/storage/media/video/anime/Show" },
              "episodeFile": { "relativePath": "Season 01/Show.S01E01.mkv" }
            }
            """.trimIndent(),
        )
        assertEquals(
            "/storage/media/video/anime/Show/Season 01/Show.S01E01.mkv",
            sonarrImportedFilePath(payload),
        )
    }

    @Test fun trims_duplicate_slash_when_joining() {
        val payload = obj(
            """
            {
              "series": { "path": "/storage/media/video/anime/Show/" },
              "episodeFile": { "relativePath": "/Season 01/Show.S01E01.mkv" }
            }
            """.trimIndent(),
        )
        assertEquals(
            "/storage/media/video/anime/Show/Season 01/Show.S01E01.mkv",
            sonarrImportedFilePath(payload),
        )
    }

    @Test fun null_when_no_episodeFile() {
        val payload = obj("""{ "eventType": "Download", "series": { "path": "/x" } }""")
        assertNull(sonarrImportedFilePath(payload))
    }

    @Test fun null_when_relativePath_without_series_path() {
        val payload = obj(
            """{ "episodeFile": { "relativePath": "Season 01/Show.S01E01.mkv" } }""",
        )
        assertNull(sonarrImportedFilePath(payload))
    }

    @Test fun null_when_episodeFile_has_neither_path() {
        val payload = obj("""{ "series": { "path": "/x" }, "episodeFile": { "size": 123 } }""")
        assertNull(sonarrImportedFilePath(payload))
    }
}
