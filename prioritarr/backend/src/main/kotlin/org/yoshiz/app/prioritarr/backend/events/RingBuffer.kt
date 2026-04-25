package org.yoshiz.app.prioritarr.backend.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Event emitted on the app event bus. [id] is a monotonically
 * increasing sequence assigned by the bus on publish. SSE clients use
 * this as `Last-Event-ID` for replay.
 */
@Serializable
data class AppEvent(
    val id: Long,
    val type: String,
    val payload: JsonElement,
)

/**
 * Thread-safe bounded ring buffer of [AppEvent]. Past events older than
 * [capacity] are silently dropped. Use [since] to replay events after a
 * given id; when the requested id is older than anything in the buffer,
 * the replay returns empty (SSE client is expected to treat that as a
 * gap and do a full reload).
 */
class RingBuffer(private val capacity: Int = 1000) {
    private val store = ArrayDeque<AppEvent>(capacity)
    private val lock = Any()

    fun append(event: AppEvent) = synchronized(lock) {
        store.addLast(event)
        while (store.size > capacity) store.removeFirst()
    }

    /** Events with id strictly greater than [lastId]. Returns empty on gap. */
    fun since(lastId: Long?): List<AppEvent> = synchronized(lock) {
        if (store.isEmpty()) return@synchronized emptyList()
        val oldestId = store.first().id
        if (lastId != null && lastId + 1 < oldestId) {
            // Gap — client missed events that have aged out.
            return@synchronized emptyList()
        }
        store.filter { lastId == null || it.id > lastId }
    }

    val size: Int get() = synchronized(lock) { store.size }
}
