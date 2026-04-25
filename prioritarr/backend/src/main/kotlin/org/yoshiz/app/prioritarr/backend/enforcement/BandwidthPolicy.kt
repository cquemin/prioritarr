package org.yoshiz.app.prioritarr.backend.enforcement

import org.yoshiz.app.prioritarr.backend.config.BandwidthSettings
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-function policy over the bandwidth settings — no I/O, no
 * mutation. Given the current clock + what's been observed lately,
 * tells the reconciler:
 *
 *   - What's the effective download cap right now?
 *   - Is the queue above the "time to pause lower bands" utilisation?
 *   - Is a specific P1 download peer-limited rather than bandwidth-
 *     limited (so pausing others wouldn't help)?
 *   - Is a specific P4/P5 close enough to finish that pausing it
 *     would waste more time than free up bandwidth?
 *
 * All speed values are bytes/second (qBit's unit) unless the name
 * says otherwise. The settings expose Mbps / kbps to match user
 * mental models; this class converts internally.
 */
object BandwidthPolicy {

    private const val MBPS_TO_BYTES_PER_SEC: Long = 125_000L   // 1 Mbps = 125 000 B/s
    private const val KBPS_TO_BYTES_PER_SEC: Long = 125L       // 1 kbps = 125 B/s

    /**
     * Effective cap in bytes/second.
     *
     *   quietModeEnabled  -> quietModeMaxMbps
     *   in peak window    -> peakHoursMaxMbps (when set; else falls through)
     *   otherwise         -> min(maxMbps, observedPeakMbps * 1.1) once both > 0
     *
     * Returns 0 when the feature is disabled (maxMbps == 0), which
     * downstream treats as "don't apply any bandwidth heuristics —
     * just use the naive pause-band rule".
     */
    fun effectiveCapBps(settings: BandwidthSettings, now: LocalTime = LocalTime.now()): Long {
        if (settings.maxMbps <= 0) return 0L
        if (settings.quietModeEnabled) return settings.quietModeMaxMbps * MBPS_TO_BYTES_PER_SEC
        if (settings.peakHoursMaxMbps != null &&
            insidePeakWindow(now, settings.peakHoursStart, settings.peakHoursEnd)
        ) {
            return settings.peakHoursMaxMbps * MBPS_TO_BYTES_PER_SEC
        }
        val manual = settings.maxMbps * MBPS_TO_BYTES_PER_SEC
        val observedBump = if (settings.observedPeakMbps > 0) {
            (settings.observedPeakMbps * 1.1 * MBPS_TO_BYTES_PER_SEC).toLong()
        } else Long.MAX_VALUE
        return minOf(manual, observedBump)
    }

    /**
     * True when the queue's current total download speed [currentBps]
     * is near enough to the cap that pausing lower bands would free
     * bandwidth for higher-band torrents.
     */
    fun utilisationExceedsThreshold(settings: BandwidthSettings, currentBps: Long, now: LocalTime = LocalTime.now()): Boolean {
        val cap = effectiveCapBps(settings, now)
        if (cap <= 0L) return true // feature disabled -> fall back to naive rule (always "pause when priority says so")
        return currentBps >= (cap * settings.utilisationThresholdPct).toLong()
    }

    /**
     * True when a P1 download looks peer-limited (low speed for
     * sustained period). Callers use this to SKIP pausing P4/P5 —
     * freeing bandwidth wouldn't help a torrent starving for peers.
     */
    fun p1IsPeerLimited(settings: BandwidthSettings, p1AverageBps: Long?): Boolean {
        if (p1AverageBps == null) return false
        val floor = settings.p1MinSpeedKbps * KBPS_TO_BYTES_PER_SEC
        return p1AverageBps < floor
    }

    /**
     * True when a candidate-for-pause is close enough to finishing
     * (within [BandwidthSettings.etaBufferMinutes]) that interrupting
     * it would waste more than it'd free up.
     */
    fun closeToFinish(settings: BandwidthSettings, etaSeconds: Long?): Boolean {
        if (etaSeconds == null || etaSeconds <= 0) return false
        return etaSeconds <= settings.etaBufferMinutes * 60L
    }

    /**
     * Parse "HH:MM" to minute-of-day; returns null on malformed input.
     * Public because the UI uses it for field validation.
     */
    internal fun parseHhmm(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * Inclusive-exclusive peak window check. Handles windows that
     * wrap midnight (e.g. 22:00-02:00 => "evening and past midnight").
     */
    internal fun insidePeakWindow(now: LocalTime, start: String?, end: String?): Boolean {
        val startMin = parseHhmm(start) ?: return false
        val endMin = parseHhmm(end) ?: return false
        val nowMin = now.hour * 60 + now.minute
        return if (startMin < endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }
}

/**
 * In-memory rolling telemetry for qBit download speeds. Keyed by
 * torrent hash. Each update appends the observed speed; the class
 * reports a simple average over the last N samples.
 *
 * Separate from the reconciler so tests can drive it deterministically
 * and so the lifecycle is obvious (one global instance, reconciler
 * hands it fresh observations each tick).
 */
class DownloadTelemetry(private val windowSize: Int = 10) {
    private val samples = ConcurrentHashMap<String, ArrayDeque<Long>>()
    @Volatile private var lastObservedPeakTotalBps: Long = 0L

    fun recordSample(hash: String, bytesPerSecond: Long) {
        val q = samples.computeIfAbsent(hash) { ArrayDeque(windowSize + 1) }
        synchronized(q) {
            q.addLast(bytesPerSecond)
            while (q.size > windowSize) q.removeFirst()
        }
    }

    fun averageBps(hash: String): Long? {
        val q = samples[hash] ?: return null
        synchronized(q) {
            if (q.isEmpty()) return null
            return q.sum() / q.size
        }
    }

    /** Record the latest observed total queue speed so the policy can auto-calibrate the cap. */
    fun recordPeakTotal(bytesPerSecond: Long) {
        if (bytesPerSecond > lastObservedPeakTotalBps) lastObservedPeakTotalBps = bytesPerSecond
    }

    fun observedPeakTotalBps(): Long = lastObservedPeakTotalBps

    /** Forget history for hashes no longer in the active queue. */
    fun prune(activeHashes: Set<String>) {
        samples.keys.retainAll(activeHashes)
    }
}
