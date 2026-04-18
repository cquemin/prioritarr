package org.yoshiz.app.prioritarr.backend.schemas

import kotlinx.serialization.Serializable

// Not modelled as a sealed hierarchy — kotlinx.serialization would inject a
// discriminator that breaks wire format parity. Handlers pick one of the
// three concrete types explicitly.

@Serializable
data class OnGrabIgnored(
    val status: String = "ignored",
    val eventType: String,
)

@Serializable
data class OnGrabProcessed(
    val status: String = "processed",
    val priority: Int,
    val label: String,
)

@Serializable
data class OnGrabDuplicate(
    val status: String = "duplicate",
    val priority: Int,
    val label: String,
)

@Serializable
data class PlexEventUnmatched(
    val status: String = "unmatched",
    val plex_key: String,
)

@Serializable
data class PlexEventOk(
    val status: String = "ok",
    val series_id: Long,
)

/** Simple status=ok envelope for test-mode endpoints. */
@Serializable
data class OkResponse(val status: String = "ok")

@Serializable
data class InjectSeriesMappingRequest(
    val plex_key: String,
    val series_id: Long,
)
