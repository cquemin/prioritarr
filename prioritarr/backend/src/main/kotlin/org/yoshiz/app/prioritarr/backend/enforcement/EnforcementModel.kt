package org.yoshiz.app.prioritarr.backend.enforcement

/**
 * Client-agnostic view of one tracked download. Built from a
 * DownloadClient.snapshotDownloads() raw row joined with the local
 * managed_downloads metadata (seriesId, seasonNumber, episodeNumber,
 * pausedByUs flag).
 *
 * Lives at the abstraction level so that [computeEnforcement] never
 * knows whether it's reasoning about a qBit hash or a SAB nzo_id.
 */
data class ManagedDownloadView(
    val client: String,            // "qbit" | "sab" | future
    val clientId: String,          // hash | nzo_id
    val priority: Int,             // 1..5
    val seriesId: Long?,
    val seasonNumber: Int?,        // null if cross-ref hasn't landed yet
    val episodeNumber: Int?,
    val state: ManagedState,
    val etaSeconds: Long?,
)

/**
 * Lifecycle state of a managed download as the enforcement layer cares
 * about it. `RUNNING` is the catch-all for "actively downloading or
 * uploading"; the rest are exclusion gates.
 */
enum class ManagedState {
    RUNNING,
    PAUSED_BY_US,        // we paused it; safe to resume when band rules allow
    PAUSED_BY_USER,      // user paused; never touch
    ERRORED,             // qBit error / SAB failed — leave alone
    NEAR_DONE,           // within etaBufferMinutes of finishing
}

/**
 * What [computeEnforcement] decided for one download. The client's
 * [DownloadClient.applyEnforcement] translates this to native verbs.
 *
 * `orderHint` ranks items globally (lower wins). For clients that only
 * honour pause/resume (qBit), the hint orders the ACTIVE set so the
 * implementation can call setTopPriority in reverse-hint order. For
 * clients that order within a bucket (SAB), the hint drives queue/switch.
 */
data class EnforcementDecision(
    val targetState: TargetState,
    val orderHint: Int,
)

enum class TargetState { ACTIVE, DEFERRED }

/**
 * Inputs to [computeEnforcement] that aren't carried per-download:
 * the bandwidth signal, the P5 ratchet flag, and predicates for the
 * existing peer-limit + near-done escape hatches. Default predicates
 * preserve today's behaviour.
 */
data class ComputeEnforcementContext(
    /**
     * Master switch for the bandwidth-aware cross-band logic. When
     * `false` (operator hasn't configured the bandwidth feature),
     * Layer 1 always defers per the priority band rule. When `true`,
     * Layer 1 only defers when [bandwidthSaturated] is also true and
     * neither [p1IsPeerLimited] nor [isNearDone] short-circuits the
     * defer.
     *
     * This boolean replaces the legacy "if (bandwidth.maxMbps <= 0)"
     * branch in the Python/old-Kotlin reconciler.
     */
    val bandwidthAwareEnabled: Boolean = false,
    /** True when the bandwidth-policy signal says "pipe is at or above the utilisation threshold". Only consulted when [bandwidthAwareEnabled] is true. */
    val bandwidthSaturated: Boolean = false,
    /** True when [P5RatchetConfig.enabled] is true AND bandwidthSaturated. */
    val p5SeasonRatchetActive: Boolean = false,
    /** P1 average speed signal — if [bandwidthAwareEnabled] but P1 is peer-limited, don't pause others. */
    val p1IsPeerLimited: Boolean = false,
    /** Per-candidate near-done predicate. Default: no item is near-done. */
    val isNearDone: (ManagedDownloadView) -> Boolean = { false },
)

/**
 * Stable raw shape returned by [DownloadClient.snapshotDownloads].
 * The client supplies what it knows natively; the reconciler enriches
 * with seriesId/seasonNumber/episodeNumber from managed_downloads +
 * Sonarr's queue, then projects to [ManagedDownloadView].
 */
data class RawDownload(
    val client: String,
    val clientId: String,
    val rawState: String,
    val pausedByUs: Boolean,
    val etaSeconds: Long?,
)
