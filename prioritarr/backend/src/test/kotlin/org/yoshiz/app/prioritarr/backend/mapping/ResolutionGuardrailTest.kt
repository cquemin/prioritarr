package org.yoshiz.app.prioritarr.backend.mapping

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Architecture guardrail: Plex resolution in business logic must go
 * through the *id* mapping ([MappingState.plexKeyForSeriesId]), never the
 * *title* mapping ([MappingState.plexKeyForSeriesTitle]).
 *
 * Sonarr and Plex titles routinely differ (anime especially), so a
 * title-based join silently drops watch history and corrupts priority.
 * Titles are for the UI only. This test fails if a future change
 * reintroduces title-based resolution into the priority or sync packages.
 */
class ResolutionGuardrailTest {

    private fun sourceDir(pkg: String): File {
        // Gradle runs the test task with the module dir as the working dir.
        val base = File("src/main/kotlin/org/yoshiz/app/prioritarr/backend")
        return File(base, pkg)
    }

    private fun kotlinSources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `business logic resolves plex by id, not by title`() {
        val dirs = listOf("priority", "sync").map { sourceDir(it) }
        dirs.forEach { assertTrue(it.isDirectory, "expected source dir ${it.path} (cwd=${File(".").absolutePath})") }

        val sources = dirs.flatMap { kotlinSources(it) }
        assertTrue(sources.isNotEmpty(), "guardrail scanned no files — path wrong, test is vacuous")

        // Non-vacuous: prove the scanner reads real content by confirming
        // the id-based accessor is actually in use somewhere it scans.
        assertTrue(
            sources.any { it.readText().contains("plexKeyForSeriesId") },
            "expected at least one priority/sync source to resolve Plex by id",
        )

        val offenders = sources.filter { it.readText().contains("plexKeyForSeriesTitle") }
        assertTrue(
            offenders.isEmpty(),
            "title-based Plex resolution is banned in business logic; offenders: ${offenders.map { it.name }}",
        )
    }
}
