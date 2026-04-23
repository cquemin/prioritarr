package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.streams.toList

/**
 * Sweeps download folders for files that don't belong. Per orphan,
 * picks one of three actions:
 *
 *   - **DELETE** — hardlink count > 1 (twin in library survives) OR
 *     Sonarr's manualimport says "Not an upgrade" (a better copy is
 *     already imported). Both cases are zero-risk: the playable file
 *     in the library remains.
 *   - **IMPORT** — Sonarr's manualimport returns the orphan with no
 *     rejection (parsed cleanly as a known series + episode). Triggers
 *     `ManualImport` command; Sonarr hardlinks/moves the file into the
 *     library and the next sweep can DELETE it.
 *   - **KEEP**  — anything else (unparseable filename, unknown series,
 *     sample-detection failure). Audit-logged for operator review;
 *     never touched.
 *
 * Source-of-truth for "is this file tracked": qBit torrent names +
 * SAB queue/history names. An entry whose name appears in any of
 * those is left alone (it's an active download, not orphan).
 *
 * Empty directories at the top level of each cleanup path get
 * reaped at the end of the sweep.
 *
 * `dryRun=true` enumerates every classification but never acts —
 * no Sonarr commands fired, no files deleted.
 */
class OrphanReaper(
    private val qbit: QBitClient,
    private val sab: SABClient,
    private val sonarr: SonarrClient,
    private val db: Database,
    private val cleanupPaths: List<String>,
    /**
     * When true, the reaper triggers Sonarr ManualImport for orphans
     * that Sonarr is willing to import. When false, it just reports
     * them ("import_pending") for the operator to action manually.
     */
    private val autoImport: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(OrphanReaper::class.java)

    suspend fun sweep(dryRun: Boolean): OrphanReport {
        val tracked = collectTrackedNames()
        val report = OrphanReport()
        for (path in cleanupPaths) {
            sweepPath(Paths.get(path), tracked, dryRun, report)
        }
        logger.info(
            "orphan-reaper: paths={} matched={} deleted={} imported={} importPending={} kept={} emptyDirs={} dryRun={}",
            cleanupPaths.size, report.matched, report.deleted, report.imported,
            report.importPending, report.kept, report.emptyDirsRemoved, dryRun,
        )
        return report
    }

    private suspend fun sweepPath(
        root: Path,
        tracked: Set<String>,
        dryRun: Boolean,
        report: OrphanReport,
    ) {
        if (!Files.isDirectory(root)) {
            logger.debug("orphan-reaper: skipping missing path {}", root)
            return
        }
        val entries = try {
            Files.list(root).use { it.toList() }
        } catch (e: Exception) {
            logger.warn("orphan-reaper: list failed for {}: {}", root, e.message)
            return
        }
        for (entry in entries) {
            if (entry.name in tracked) {
                report.matched++
                continue
            }
            classifyAndAct(entry, dryRun, report)
        }
        if (!dryRun) cleanEmptyDirs(root, report)
    }

    /**
     * Decide one of {DELETE, IMPORT, KEEP} for [entry] and fire the
     * corresponding side-effect (or just log when [dryRun]).
     */
    private suspend fun classifyAndAct(entry: Path, dryRun: Boolean, report: OrphanReport) {
        val (size, maxLinks) = aggregateStats(entry)

        // Cheap path 1 — hardlinked twin in library, definitely safe to drop.
        if (maxLinks > 1) {
            deleteAction(entry, size, "hardlink twin in library", dryRun, report)
            return
        }

        // Ask Sonarr. The probe yields: zero items (no parse), a single
        // item with no rejections (importable), or rejection reasons.
        val probe = try {
            probeSonarr(entry)
        } catch (e: Exception) {
            logger.debug("orphan-reaper: sonarr probe failed for {}: {}", entry, e.message)
            keepAction(entry, size, "sonarr probe failed: ${e.message}", report)
            return
        }
        if (probe == null || probe.size == 0) {
            keepAction(entry, size, "Sonarr can't parse this file", report)
            return
        }
        val item = probe[0].jsonObject
        val rejections = (item["rejections"] as? JsonArray).orEmpty()
        if (rejections.isEmpty()) {
            // Importable — fire ManualImport (or report it pending).
            importAction(item, entry, size, dryRun, report)
            return
        }
        val reasons = rejections.map { (it as JsonObject)["reason"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        // Cheap path 2 — Sonarr says "Not an upgrade" → a better copy
        // already lives in the library. Safe to delete.
        if (reasons.any { it.contains("Not an upgrade") || it.contains("Not a quality revision upgrade") }) {
            deleteAction(entry, size, "not an upgrade: ${reasons.first().take(80)}", dryRun, report)
            return
        }
        keepAction(entry, size, reasons.joinToString("; ").take(160), report)
    }

    private suspend fun importAction(
        item: JsonObject,
        entry: Path,
        size: Long,
        dryRun: Boolean,
        report: OrphanReport,
    ) {
        if (!autoImport) {
            report.importPending++
            audit(report, "orphan_reaper_import_pending", entry, size, "Sonarr-importable; auto-import disabled")
            return
        }
        if (dryRun) {
            report.imported++  // would have imported
            audit(report, "orphan_reaper_dry_import", entry, size, "would import via Sonarr")
            return
        }
        try {
            sonarr.triggerManualImport(item)
            report.imported++
            audit(report, "orphan_reaper_import", entry, size, "queued ManualImport")
        } catch (e: Exception) {
            logger.warn("orphan-reaper: ManualImport failed for {}: {}", entry, e.message)
            report.errors++
            audit(report, "orphan_reaper_import_error", entry, size, e.message.orEmpty())
        }
    }

    private fun deleteAction(
        entry: Path, size: Long, reason: String, dryRun: Boolean, report: OrphanReport,
    ) {
        report.deleted++
        report.deletedBytes += size
        if (!dryRun) {
            try {
                deleteRecursive(entry)
            } catch (e: Exception) {
                logger.warn("orphan-reaper: delete failed for {}: {}", entry, e.message)
                report.errors++
            }
        }
        audit(report, if (dryRun) "orphan_reaper_dry_delete" else "orphan_reaper_delete", entry, size, reason)
    }

    private fun keepAction(entry: Path, size: Long, reason: String, report: OrphanReport) {
        report.kept++
        report.keptBytes += size
        audit(report, "orphan_reaper_keep", entry, size, reason)
    }

    /**
     * Per-file probe of Sonarr's manualimport endpoint. The whole-folder
     * variant hangs on large folders (mediainfo all at once); per-file
     * stays in the ~1s range.
     */
    private suspend fun probeSonarr(entry: Path): JsonArray? {
        // Sonarr sees the same path because we mount the same /storage.
        // Path is sent as a query param, URL-encoded by the client.
        return sonarr.manualImportProbe(entry.toString())
    }

    private fun aggregateStats(entry: Path): Pair<Long, Int> {
        if (!Files.isDirectory(entry)) {
            return try {
                val size = entry.fileSize()
                val links = (Files.getAttribute(entry, "unix:nlink") as? Number)?.toInt() ?: 1
                size to links
            } catch (_: Exception) { 0L to 1 }
        }
        var totalSize = 0L
        var maxLinks = 1
        Files.walk(entry).use { stream ->
            for (p in stream) {
                if (Files.isDirectory(p)) continue
                try {
                    totalSize += p.fileSize()
                    val links = (Files.getAttribute(p, "unix:nlink") as? Number)?.toInt() ?: 1
                    if (links > maxLinks) maxLinks = links
                } catch (_: Exception) { /* skip unreadable */ }
            }
        }
        return totalSize to maxLinks
    }

    private fun deleteRecursive(p: Path) {
        if (Files.isDirectory(p)) {
            Files.walk(p).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(p)
        }
    }

    private fun cleanEmptyDirs(root: Path, report: OrphanReport) {
        try {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    if (p == root) return@forEach
                    if (Files.isDirectory(p) && Files.list(p).use { it.toList().isEmpty() }) {
                        try {
                            Files.deleteIfExists(p)
                            report.emptyDirsRemoved++
                        } catch (_: Exception) { /* skip */ }
                    }
                }
            }
        } catch (_: Exception) { /* skip */ }
    }

    private suspend fun collectTrackedNames(): Set<String> {
        val out = HashSet<String>()
        try {
            for (t in qbit.getTorrents()) {
                val obj = (t as? JsonObject) ?: continue
                obj["name"]?.jsonPrimitive?.contentOrNull?.let(out::add)
                obj["content_path"]?.jsonPrimitive?.contentOrNull?.let { cp ->
                    out += cp.substringAfterLast('/').substringAfterLast('\\')
                }
            }
        } catch (e: Exception) {
            logger.warn("orphan-reaper: qbit torrent fetch failed: {}", e.message)
        }
        try {
            for (s in sab.getQueue()) {
                val obj = (s as? JsonObject) ?: continue
                obj["filename"]?.jsonPrimitive?.contentOrNull?.let(out::add)
                obj["nzo_id"]?.jsonPrimitive?.contentOrNull?.let(out::add)
            }
        } catch (e: Exception) {
            logger.warn("orphan-reaper: sab queue fetch failed: {}", e.message)
        }
        try {
            for (s in sab.getHistory(limit = 5000)) {
                val obj = (s as? JsonObject) ?: continue
                obj["name"]?.jsonPrimitive?.contentOrNull?.let(out::add)
                obj["storage"]?.jsonPrimitive?.contentOrNull?.let { storage ->
                    out += storage.substringAfterLast('/').substringAfterLast('\\')
                }
            }
        } catch (e: Exception) {
            logger.warn("orphan-reaper: sab history fetch failed: {}", e.message)
        }
        return out
    }

    private fun audit(
        report: OrphanReport,
        action: String,
        entry: Path,
        size: Long,
        reason: String,
    ) {
        report.entries += OrphanReport.Entry(
            action = action,
            path = entry.toString(),
            sizeBytes = size,
            reason = reason,
        )
        try {
            val safePath = entry.toString().replace("\\", "\\\\").replace("\"", "\\\"")
            val safeReason = reason.replace("\\", "\\\\").replace("\"", "\\\"")
            db.appendAudit(
                action = action,
                seriesId = null,
                client = null,
                clientId = null,
                details = Json.parseToJsonElement(
                    """{"path":"$safePath","size_bytes":$size,"reason":"$safeReason"}""",
                ),
            )
        } catch (_: Exception) { /* audit best-effort */ }
    }
}

/** Per-sweep aggregate. Returned to callers (logs, API endpoints, tests). */
@Serializable
data class OrphanReport(
    var matched: Int = 0,
    var deleted: Int = 0,
    var imported: Int = 0,
    var importPending: Int = 0,
    var kept: Int = 0,
    var emptyDirsRemoved: Int = 0,
    var errors: Int = 0,
    var deletedBytes: Long = 0L,
    var keptBytes: Long = 0L,
    val entries: MutableList<Entry> = mutableListOf(),
) {
    @Serializable
    data class Entry(
        val action: String,
        val path: String,
        val sizeBytes: Long,
        val reason: String = "",
    )
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
