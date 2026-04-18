package org.cquemin.prioritarr.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Project-standard JSON config — matches FastAPI output semantics. */
val ClientJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Default client for Sonarr / Tautulli / SAB: JSON, 120s timeouts. */
fun defaultJsonClient(timeoutMs: Long = 120_000): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(ClientJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMs
        connectTimeoutMillis = timeoutMs
        socketTimeoutMillis = timeoutMs
    }
}

/** Plex uses XML for /library endpoints — no JSON auto-conversion. */
fun xmlClient(timeoutMs: Long = 60_000): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMs
        connectTimeoutMillis = timeoutMs
        socketTimeoutMillis = timeoutMs
    }
}

/** qBittorrent needs cookie persistence for auth — 30s timeouts. */
fun qbitClient(timeoutMs: Long = 30_000): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(ClientJson) }
    install(HttpCookies)
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMs
        connectTimeoutMillis = timeoutMs
        socketTimeoutMillis = timeoutMs
    }
    expectSuccess = true
}
