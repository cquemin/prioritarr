package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable

/** Emitted by GET /health on 200. */
@Serializable
data class HealthOk(val status: String = "ok")

/** Emitted by GET /health on 503. */
@Serializable
data class HealthUnhealthy(
    val status: String = "unhealthy",
    val reason: String,
)
