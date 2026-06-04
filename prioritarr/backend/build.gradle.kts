import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.shadow)
    application
}

group = "org.yoshiz.app.prioritarr"

// Run git via the provider-based exec API so it is compatible with the
// Gradle configuration cache (starting external processes directly at
// configuration time is unsupported there). Returns null on any failure.
fun runGit(vararg args: String): String? = runCatching {
    val result = providers.exec {
        workingDir = rootProject.projectDir
        commandLine(listOf("git") + args)
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) {
        result.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }
}.getOrNull()

val appVersion: String = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }
    ?: runGit("describe", "--tags", "--always", "--dirty")
    ?: "0.0.0-dev"
val gitSha: String = (findProperty("gitSha") as String?)?.takeIf { it.isNotBlank() }
    ?: runGit("rev-parse", "--short", "HEAD")
    ?: "unknown"

version = appVersion

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.cors)

    // Ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Serialization
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // SQLDelight
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqldelight.coroutines.extensions)

    // Config
    implementation(libs.snakeyaml)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "org.yoshiz.app.prioritarr.backend.MainKt"
}

val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo")

val generateBuildInfo by tasks.registering {
    // Capture into locals so the doLast lambda closes over plain
    // values (String / File) rather than Gradle script object
    // references, which the configuration cache cannot serialize.
    val versionValue = appVersion
    val gitShaValue = gitSha
    val outputFile = buildInfoDir.map { it.file("build-info.properties") }
    inputs.property("version", versionValue)
    inputs.property("gitSha", gitShaValue)
    outputs.dir(buildInfoDir)
    doLast {
        val f = outputFile.get().asFile
        f.parentFile.mkdirs()
        val buildTime = OffsetDateTime
            .now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_INSTANT)
        f.writeText("version=$versionValue\ngitSha=$gitShaValue\nbuildTime=$buildTime\n")
    }
}

sourceSets.named("main") { resources.srcDir(buildInfoDir) }
tasks.named("processResources") { dependsOn(generateBuildInfo) }

sqldelight {
    databases {
        create("Db") {
            packageName.set("org.yoshiz.app.prioritarr.backend.database")
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Fat jar configuration — Docker image runs `java -jar prioritarr.jar`.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("prioritarr")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

// Copy the committed openapi.json from the repo root into the built
// resources jar so /openapi.json can serve it byte-identical (Spec B §8.1).
val copyOpenapiJson by tasks.registering(Copy::class) {
    from("${rootProject.projectDir}/../openapi.json")
    into("src/main/resources")
}
tasks.named("processResources") { dependsOn(copyOpenapiJson) }
