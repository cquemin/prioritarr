package org.yoshiz.app.prioritarr.backend.events

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingBufferTest {
    @Test
    fun `wraps beyond capacity`() {
        val b = RingBuffer(capacity = 3)
        for (i in 1..5) b.append(AppEvent(i.toLong(), "t", JsonPrimitive("p$i")))
        val all = b.since(null)
        assertEquals(3, all.size, "capacity=3 keeps only the last 3 events")
        assertEquals(listOf(3L, 4L, 5L), all.map { it.id })
    }

    @Test
    fun `since returns only later events`() {
        val b = RingBuffer(capacity = 10)
        for (i in 1..5) b.append(AppEvent(i.toLong(), "t", JsonPrimitive(i)))
        val after2 = b.since(2)
        assertEquals(listOf(3L, 4L, 5L), after2.map { it.id })
    }

    @Test
    fun `since returns empty on gap`() {
        val b = RingBuffer(capacity = 3)
        for (i in 1..10) b.append(AppEvent(i.toLong(), "t", JsonPrimitive(i)))
        // Buffer now holds ids 8,9,10. Client asking for >2 misses 3..7.
        assertTrue(b.since(2).isEmpty(), "gap should return empty so client reloads")
    }

    @Test
    fun `since null returns everything in buffer`() {
        val b = RingBuffer(capacity = 10)
        for (i in 1..3) b.append(AppEvent(i.toLong(), "t", JsonPrimitive(i)))
        assertEquals(3, b.since(null).size)
    }
}

class EventBusTest {
    @Test
    fun `publish assigns monotonic ids and stores in buffer`() {
        val bus = EventBus()
        val e1 = bus.publish("a", JsonPrimitive(1))
        val e2 = bus.publish("b", JsonPrimitive(2))
        assertEquals(1L, e1.id)
        assertEquals(2L, e2.id)
        assertEquals(2, bus.buffer.size)
    }

    @Test
    fun `subscribers receive published events`() = runTest {
        val bus = EventBus()
        val collected = async { bus.flow.take(2).toList() }
        kotlinx.coroutines.yield()
        bus.publish("x", JsonPrimitive(1))
        bus.publish("y", JsonPrimitive(2))
        val got: List<AppEvent> = collected.await()
        assertEquals(listOf("x", "y"), got.map { it.type })
    }
}
