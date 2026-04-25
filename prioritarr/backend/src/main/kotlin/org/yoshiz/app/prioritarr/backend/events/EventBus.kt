package org.yoshiz.app.prioritarr.backend.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * App-wide event bus. Handlers call [publish] to emit an event; SSE
 * subscribers collect [flow] for live events and call [buffer].since()
 * for reconnect replay.
 *
 * Uses a [MutableSharedFlow] with replay=0 — the ring buffer owns
 * replay semantics; the flow is purely live.
 */
class EventBus(val buffer: RingBuffer = RingBuffer()) {
    private val nextId = AtomicLong(1)
    private val _flow = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    val flow: SharedFlow<AppEvent> get() = _flow.asSharedFlow()

    /** Publish an event. Non-suspending; drops into the buffer + flow. */
    fun publish(type: String, payload: JsonElement): AppEvent {
        val event = AppEvent(id = nextId.getAndIncrement(), type = type, payload = payload)
        buffer.append(event)
        // tryEmit succeeds when there's buffer capacity; on overflow we
        // drop the event for slow subscribers rather than blocking the
        // publisher. The ring buffer still has it for reconnect replay.
        _flow.tryEmit(event)
        return event
    }
}
