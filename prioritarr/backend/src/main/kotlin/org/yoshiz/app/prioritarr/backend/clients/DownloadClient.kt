package org.yoshiz.app.prioritarr.backend.clients

import kotlinx.serialization.json.JsonArray

/**
 * Common surface for single-item download-client actions. Covers the
 * verbs the UI and bulk endpoints fire: pause, resume, boost, demote,
 * delete, and a per-client translation of the prioritarr P1–P5 label.
 *
 * **Not** unified at the enforcement-strategy level — qBit uses
 * pause-band semantics (needs global knowledge of the active queue),
 * SAB uses priority buckets (per-item). Each client owns its own
 * reconciler (`reconcileQbit`, `reconcileSab`) and the enforcement
 * logic stays there. The interface lets route handlers and the bulk
 * endpoint dispatch by client name without switching on hardcoded
 * strings.
 *
 * ### Adding a third downloader (e.g. Transmission, NZBGet)
 *
 *   1. Implement this interface on the new client class. Match the
 *      `clientName` on every call site that stores a discriminator
 *      (managed_downloads.client, audit entries, UI chips).
 *   2. Add a `reconcileTransmission` (or similar) function mirroring
 *      the existing pattern in `reconcile/` — the enforcement strategy
 *      is client-specific (band vs. bucket vs. other).
 *   3. Wire the new instance into [AppState.downloadClients] from
 *      Main.kt, keyed on its `clientName`. Routes pick it up
 *      automatically.
 *   4. Extend `managed_downloads` access patterns if the client has a
 *      different id shape (qBit: infohash, SAB: nzo_id — both are
 *      just strings so no migration is usually needed).
 */
interface DownloadClient {
    /**
     * Stable identifier used as the `managed_downloads.client`
     * discriminator, in audit entries, and as the lookup key in
     * [AppState.downloadClients]. Lowercase, no spaces.
     */
    val clientName: String

    /** Pause a specific download by its client-side id. */
    suspend fun pauseOne(clientId: String)

    /** Resume a previously-paused download. */
    suspend fun resumeOne(clientId: String)

    /** Move to top of the client's native ordering. */
    suspend fun boostOne(clientId: String)

    /** Move to bottom of the client's native ordering. */
    suspend fun demoteOne(clientId: String)

    /**
     * Apply the prioritarr P1–P5 label to this download by
     * translating to the client's native model. For SAB that's a
     * priority bucket; for qBit it's an immediate pause/resume
     * decision based on the active-queue band rule.
     */
    suspend fun applyPriority(clientId: String, prioritarrPriority: Int)

    /**
     * Fully remove a download from the client. [deleteFiles]=true
     * also reclaims the bytes from disk — the common case, because
     * once a user asks to delete a "ghost" download they almost
     * always want its partial file gone too. Leaving [deleteFiles]=
     * false is for the rare case of "I already moved the file
     * somewhere and just want the client to stop tracking it."
     */
    suspend fun deleteOne(clientId: String, deleteFiles: Boolean = true)
}
