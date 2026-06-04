package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable

@Serializable
data class VersionResponse(
    val version: String,
    val gitSha: String,
    val buildTime: String,
)
