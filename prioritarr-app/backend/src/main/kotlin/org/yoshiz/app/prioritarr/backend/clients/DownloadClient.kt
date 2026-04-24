package org.yoshiz.app.prioritarr.backend.clients

/**
 * Common surface for download clients (qBittorrent, SABnzbd, and
 * future additions like Transmission / NZBGet). **Not** currently
 * used as a call-site abstraction — the reconciler and route
 * handlers still call [QBitClient] and [SABClient] concretely — but
 * documents the minimal operations any new downloader must support
 * to slot in.
 *
 * When adding a third downloader:
 *   1. Implement this interface on the new client.
 *   2. Add a [managed_downloads.client] discriminator value
 *      (e.g. "transmission") throughout reconcile / route switches.
 *   3. If the new downloader has a distinct priority model, extend
 *      the PRIORITY_MAP pattern on [SABClient.Companion].
 *
 * A future PR can swap the concrete call sites over to this
 * interface so reconcile + routes become source-agnostic. Kept as
 * a documentation interface until then to avoid churning the
 * existing implementations.
 */
interface DownloadClient {
    /** Stable identifier used as the `client` discriminator in the DB + UI. */
    val clientName: String

    /**
     * Snapshot of every job/torrent the downloader tracks, in a
     * client-specific JSON shape. The reconciler pattern-matches on
     * fields it cares about (hash/nzo_id, state, priority, paused).
     */
    suspend fun listDownloads(): kotlinx.serialization.json.JsonArray

    /** Pause a specific download by its client-side id. */
    suspend fun pauseOne(clientId: String)

    /** Resume a previously-paused download. */
    suspend fun resumeOne(clientId: String)

    /**
     * Apply a prioritarr P1–P5 priority to this download. Each
     * client translates differently (qBit: pause/boost; SAB: native
     * priority bucket). The translation table lives on each
     * implementation.
     */
    suspend fun setPriority(clientId: String, prioritarrPriority: Int)
}
