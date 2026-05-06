package org.yoshiz.app.prioritarr.backend.clients

import kotlinx.serialization.json.JsonArray

/**
 * Common surface for single-item download-client actions. Covers the
 * verbs the UI and bulk endpoints fire: pause, resume, boost, demote,
 * delete, and a per-client translation of the prioritarr P1–P5 label.
 *
 * Enforcement strategy IS unified at the calculation level
 * ([org.yoshiz.app.prioritarr.backend.enforcement.computeEnforcement]).
 * Each client's [applyEnforcement] translates the calculated
 * decisions to its native API; qBit emits pause/resume + setTopPriority,
 * SAB walks its priority bucket via queue/switch.
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

    /**
     * Snapshot the client's queue, normalised to [RawDownload]. The
     * caller (reconciler) joins these against [Database.listManagedDownloads]
     * and Sonarr's queue to produce [ManagedDownloadView]s for
     * [computeEnforcement]. Returning an empty list on transient
     * errors is preferred over throwing — the reconciler is supervised
     * but a hot-loop stack trace is noise.
     */
    suspend fun snapshotDownloads(): List<org.yoshiz.app.prioritarr.backend.enforcement.RawDownload>

    /**
     * Apply the calculated decisions for items belonging to this
     * client. The implementation is free to translate ACTIVE/DEFERRED
     * + orderHint to whatever native API achieves the same observable
     * effect with minimum churn.
     *
     * Decisions for items not belonging to this client (different
     * `client` field) MUST be ignored — the reconciler filters before
     * dispatching, but defence in depth is cheap.
     */
    suspend fun applyEnforcement(
        decisions: Map<String, org.yoshiz.app.prioritarr.backend.enforcement.EnforcementDecision>,
    )
}
