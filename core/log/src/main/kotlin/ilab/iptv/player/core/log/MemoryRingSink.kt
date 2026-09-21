package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.LogEvent

/**
 * In-memory ring of the most recent [capacity] events (docs/03 §5), read by the in-app log console
 * (docs/03 §7.2). Fixed size with overwrite-on-full: O(1) write, no allocation churn, no Android
 * dependency (plain JVM, unit-testable off-device).
 */
class MemoryRingSink(
    override val id: String = ID,
    val capacity: Int = DEFAULT_CAPACITY,
) : LogSink {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(id.isNotBlank()) { "id must not be blank" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<LogEvent>(capacity)
    private var evicted = 0L

    override fun write(event: LogEvent) {
        synchronized(lock) {
            if (buffer.size == capacity) {
                buffer.removeFirst()
                evicted++
            }
            buffer.addLast(event)
        }
    }

    /** No buffering of its own: the ring *is* the buffer, so a flush is a no-op. */
    override fun flush() = Unit

    /** Oldest-first copy of the ring; safe to iterate while writes continue. */
    fun snapshot(): List<LogEvent> = synchronized(lock) { buffer.toList() }

    /** The newest [count] events, oldest-first (what the console renders). */
    fun tail(count: Int): List<LogEvent> = synchronized(lock) {
        if (count <= 0) return emptyList()
        if (count >= buffer.size) return buffer.toList()
        buffer.toList().subList(buffer.size - count, buffer.size)
    }

    val size: Int get() = synchronized(lock) { buffer.size }

    /** How many events fell out of the ring because it was full (a filled ring is normal). */
    val evictedCount: Long get() = synchronized(lock) { evicted }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        const val ID = "memory-ring"

        /** docs/03 §5 / §14: 2000 events by default, configurable 512–10000. */
        const val DEFAULT_CAPACITY = 2000
    }
}
