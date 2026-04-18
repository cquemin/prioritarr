package org.yoshiz.app.prioritarr.backend.enforcement

import org.yoshiz.app.prioritarr.backend.clients.SABClient

/** Map internal P1..P5 → SAB priority value. Mirrors enforcement.py. */
fun computeSabPriority(prioritarrLevel: Int): Int =
    SABClient.PRIORITY_MAP[prioritarrLevel] ?: 0

/** One scheduled qBit action. */
data class QBitAction(val hash: String, val action: String) // pause|resume|top_priority

/** One entry in the [downloads] map given to [computeQBitPauseActions]. */
data class QBitDownloadView(
    val hash: String,
    val priority: Int,
    val state: String,
    val pausedByUs: Boolean,
)

/**
 * Pause-band rules (Spec A §4, enforcement.py parity):
 * - P1 active → pause P4 + P5
 * - P2 active (no P1) → pause P5
 * - Only P3/P4/P5 → nothing paused
 * - Every P1 also gets a top_priority action so qBit schedules it first.
 */
fun computeQBitPauseActions(downloads: Collection<QBitDownloadView>): List<QBitAction> {
    val activePriorities = downloads
        .filter { it.state !in PAUSED_OR_ERRORED }
        .map { it.priority }
        .toSet()

    val hasP1 = 1 in activePriorities
    val hasP2 = 2 in activePriorities
    val pauseLevels: Set<Int> = when {
        hasP1 -> setOf(4, 5)
        hasP2 -> setOf(5)
        else -> emptySet()
    }

    val actions = mutableListOf<QBitAction>()
    for (d in downloads) {
        val isPaused = d.state in PAUSED_STATES
        if (d.priority in pauseLevels && !isPaused) {
            actions += QBitAction(d.hash, "pause")
        } else if (d.priority !in pauseLevels && isPaused && d.pausedByUs) {
            actions += QBitAction(d.hash, "resume")
        }
        if (d.priority == 1 && !isPaused) {
            actions += QBitAction(d.hash, "top_priority")
        }
    }
    return actions
}

private val PAUSED_STATES = setOf("pausedDL", "pausedUP")
private val PAUSED_OR_ERRORED = PAUSED_STATES + setOf("error", "missingFiles")
