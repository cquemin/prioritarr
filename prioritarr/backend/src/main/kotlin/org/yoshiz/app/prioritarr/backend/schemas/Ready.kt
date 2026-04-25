package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DependencyStatus {
    @SerialName("ok") OK,
    @SerialName("unreachable") UNREACHABLE,
}

@Serializable
data class ReadyResponse(
    val status: String,
    val dependencies: Map<String, DependencyStatus>,
    val last_heartbeat: String?,
)
