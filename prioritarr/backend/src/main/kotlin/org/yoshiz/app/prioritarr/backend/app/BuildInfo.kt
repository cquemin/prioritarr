package org.yoshiz.app.prioritarr.backend.app

import java.util.Properties

/**
 * Build provenance baked into the jar at build time by the
 * `generateBuildInfo` Gradle task. Falls back to "unknown" so unit
 * tests / IDE runs without the generated resource don't crash.
 */
object BuildInfo {
    private val props: Properties = Properties().apply {
        BuildInfo::class.java.classLoader
            .getResourceAsStream("build-info.properties")
            ?.use { load(it) }
    }
    val version: String = props.getProperty("version", "unknown")
    val gitSha: String = props.getProperty("gitSha", "unknown")
    val buildTime: String = props.getProperty("buildTime", "unknown")
}
