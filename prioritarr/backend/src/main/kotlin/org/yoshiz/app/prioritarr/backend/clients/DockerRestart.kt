package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.post

/**
 * Restarts containers through a scoped docker-socket-proxy (HTTP Docker API).
 * Only ever used for the Sonarr watchdog container-restart fallback.
 */
class DockerRestartClient(
    baseUrl: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    /** POST /containers/{name}/restart — Docker Engine API restart. */
    suspend fun restartContainer(name: String) {
        http.post("$root/containers/$name/restart")
    }
}
