package org.cquemin.prioritarr.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField.NANO_OF_SECOND
import java.time.temporal.ChronoUnit

/**
 * Kotlin counterpart of `prioritarr/database.py` Database class.
 * Public surface and semantics match 1:1 so a reader can diff either
 * implementation and see the same thing. The SQLDelight-generated
 * [Db]/[SchemaQueries] layer underneath is an implementation detail.
 */
class Database(dbPath: String) {

    private val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
    private val db: Db

    init {
        Db.Schema.create(driver)
        db = Db(driver)
    }

    /** The underlying generated queries — exposed for advanced callers (mapping cache warm-up, tests). */
    val q: SchemaQueries get() = db.schemaQueries

    // ------------------------------------------------------------------
    // series_priority_cache
    // ------------------------------------------------------------------

    fun upsertPriorityCache(
        seriesId: Long,
        priority: Long,
        watchPct: Double?,
        daysSinceWatch: Long?,
        unwatchedPending: Long?,
        computedAt: String,
        expiresAt: String,
        reason: String?,
    ) {
        q.upsertPriorityCache(
            series_id = seriesId,
            priority = priority,
            watch_pct = watchPct,
            days_since_watch = daysSinceWatch,
            unwatched_pending = unwatchedPending,
            computed_at = computedAt,
            expires_at = expiresAt,
            reason = reason,
        )
    }

    fun getPriorityCache(seriesId: Long): Series_priority_cache? =
        q.selectPriorityCache(seriesId).executeAsOneOrNull()

    fun invalidatePriorityCache(seriesId: Long) {
        q.invalidatePriorityCache(seriesId)
    }

    // ------------------------------------------------------------------
    // managed_downloads
    // ------------------------------------------------------------------

    fun upsertManagedDownload(
        client: String,
        clientId: String,
        seriesId: Long,
        episodeIds: List<Long>,
        initialPriority: Long,
        currentPriority: Long,
        pausedByUs: Boolean,
        firstSeenAt: String,
        lastReconciledAt: String,
    ) {
        q.upsertManagedDownload(
            client = client,
            client_id = clientId,
            series_id = seriesId,
            episode_ids = Json.encodeToString(ListSerializer(Long.serializer()), episodeIds),
            initial_priority = initialPriority,
            current_priority = currentPriority,
            paused_by_us = if (pausedByUs) 1L else 0L,
            first_seen_at = firstSeenAt,
            last_reconciled_at = lastReconciledAt,
        )
    }

    fun getManagedDownload(client: String, clientId: String): Managed_downloads? =
        q.selectManagedDownload(client, clientId).executeAsOneOrNull()

    fun listManagedDownloads(client: String? = null): List<Managed_downloads> =
        if (client == null) q.listManagedDownloads().executeAsList()
        else q.listManagedDownloadsByClient(client).executeAsList()

    fun deleteManagedDownload(client: String, clientId: String) {
        q.deleteManagedDownload(client, clientId)
    }

    // ------------------------------------------------------------------
    // webhook_dedupe
    // ------------------------------------------------------------------

    /**
     * Insert [eventKey] with [receivedAt]. Returns `true` if the row was
     * new, `false` if it was already present (dedupe hit). Matches the
     * return contract of `prioritarr/database.py::try_insert_dedupe`.
     *
     * The `changes()` read must happen inside the same transaction as
     * the INSERT OR IGNORE, otherwise autoCommit resets the counter
     * before we can read it.
     */
    fun tryInsertDedupe(eventKey: String, receivedAt: String): Boolean =
        db.transactionWithResult {
            q.tryInsertDedupe(eventKey, receivedAt)
            q.dedupeChanges().executeAsOne() > 0
        }

    // ------------------------------------------------------------------
    // audit_log
    // ------------------------------------------------------------------

    fun appendAudit(
        action: String,
        seriesId: Long? = null,
        client: String? = null,
        clientId: String? = null,
        details: JsonElement? = null,
    ) {
        val ts = nowIsoOffset()
        val detailsJson = details?.let { Json.encodeToString(JsonElement.serializer(), it) }
        q.appendAudit(
            ts = ts,
            action = action,
            series_id = seriesId,
            client = client,
            client_id = clientId,
            details = detailsJson,
        )
    }

    // ------------------------------------------------------------------
    // heartbeat
    // ------------------------------------------------------------------

    fun updateHeartbeat() {
        q.upsertHeartbeat(nowIsoOffset())
    }

    fun getHeartbeat(): String? = q.selectHeartbeat().executeAsOneOrNull()

    // ------------------------------------------------------------------
    // prune
    // ------------------------------------------------------------------

    /** Test-mode only: truncate every mutable table in one transaction. */
    fun resetAllState() {
        db.transaction {
            q.deleteAllManagedDownloads()
            q.deleteAllWebhookDedupe()
            q.deleteAllAuditLog()
            q.deleteAllSeriesPriorityCache()
            q.deleteAllHeartbeat()
        }
    }

    fun prune(dedupeHours: Long = 24, retentionDays: Long = 90) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val dedupeCutoff = now.minus(dedupeHours, ChronoUnit.HOURS).format(ISO_OFFSET)
        val auditCutoff = now.minus(retentionDays, ChronoUnit.DAYS).format(ISO_OFFSET)
        q.pruneDedupe(dedupeCutoff)
        q.pruneAudit(auditCutoff)
    }

    companion object {
        /**
         * ISO 8601 with explicit '+00:00' offset (NOT 'Z') — matches
         * Python's `datetime.now(timezone.utc).isoformat()`. Spec B §5.2
         * and the contract test regex `\+\d{2}:\d{2}$` require this.
         *
         * Can't use [DateTimeFormatter.ISO_OFFSET_DATE_TIME] directly
         * because the JDK prints `Z` for zero offset. We build a custom
         * formatter that pins the zero-offset rendering to `+00:00`.
         */
        val ISO_OFFSET: DateTimeFormatter = DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .append(DateTimeFormatter.ofPattern("HH:mm:ss"))
            .appendFraction(NANO_OF_SECOND, 6, 6, true)
            .appendOffset("+HH:MM", "+00:00")
            .toFormatter()

        fun nowIsoOffset(): String =
            OffsetDateTime.now(ZoneOffset.UTC).format(ISO_OFFSET)
    }
}
