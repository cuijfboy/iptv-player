package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.Redactor
import ilab.iptv.player.core.common.SessionIdFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process uptime in ms (docs/03 §4 `elapsedMs`), e.g. `SystemClock.elapsedRealtime()`. */
fun interface UptimeMs {
    fun readMs(): Long
}

/**
 * Logger facade implementation + multi-sink fan-out (docs/02 §4.1, docs/03 §2/§5).
 *
 * Pipeline: `emit → level filter → envelope stamping → redaction → bounded queue → single
 * dispatch thread → every [LogSink]`. Callers never block on a sink, and one broken sink cannot
 * stop the others or the calling thread.
 *
 * Envelope contract (CR-05): `seq`, `ts`, `elapsedMs`, `thread` and `sessionId` are stamped here
 * from the injected [Clock] / [UptimeMs] / [SessionIdFactory] and the current thread. If any of
 * them cannot be produced as a real value the event is **dropped** (counted in
 * [envelopeFailureCount]) — a wrong envelope poisons `sessionId` folding, `seq` ordering and
 * `elapsedMs` stall detection, so no event is better than a fake one.
 */
class LogBus(
    private val sinks: Set<LogSink>,
    private val clock: Clock,
    private val uptime: UptimeMs,
    private val sessionIds: SessionIdFactory,
    private val redactor: Redactor? = null,
    initialMinLevel: LogLevel = LogLevel.INFO,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val executor: Executor = newDispatchExecutor(),
) : Logger {

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive, was $queueCapacity" }
    }

    private val sequence = AtomicLong(0L)
    private val currentSession = AtomicReference(sessionIds.newId(SESSION_PREFIX))
    private val queue = LinkedBlockingQueue<LogEvent>(queueCapacity)
    private val pending = AtomicInteger(0)
    private val dropped = AtomicLong(0L)
    private val filtered = AtomicLong(0L)
    private val envelopeFailures = AtomicLong(0L)
    private val sinkFailures = AtomicLong(0L)
    private val workerStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val idleLock = ReentrantLock()
    private val idle = idleLock.newCondition()

    @Volatile
    private var level: LogLevel = initialMinLevel

    /** Lowest level that is kept (docs/03 §14: dynamically switchable, default INFO). */
    var minLevel: LogLevel
        get() = level
        set(value) {
            level = value
        }

    /** Events discarded because the queue stayed full (docs/03 §5). */
    val droppedCount: Long get() = dropped.get()

    /** Events discarded by the level filter — normal and cheap, not a problem signal. */
    val filteredCount: Long get() = filtered.get()

    /** Events discarded because a real envelope value could not be produced (CR-05). */
    val envelopeFailureCount: Long get() = envelopeFailures.get()

    /** Events queued or in flight; 0 means every accepted event reached the sinks. */
    val pendingCount: Int get() = pending.get()

    /** Sink `write` calls that threw. Non-zero means a sink is broken (the others keep working). */
    val sinkFailureCount: Long get() = sinkFailures.get()

    /**
     * Opens a new correlation window (one refresh, one playback — docs/03 §4) and returns its id.
     * Shortcut calls after this carry the new `sessionId`.
     */
    fun startSession(prefix: String): String {
        val id = sessionIds.newId(prefix)
        currentSession.set(id)
        return id
    }

    fun currentSessionId(): String = currentSession.get()

    override fun log(event: LogEvent) {
        if (event.level < level) {
            filtered.incrementAndGet()
            return
        }
        enqueue(redact(event))
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        emit(LogLevel.VERBOSE, category, code, message, fields, error = null)

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        emit(LogLevel.DEBUG, category, code, message, fields, error = null)

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        emit(LogLevel.INFO, category, code, message, fields, error = null)

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = emit(LogLevel.WARN, category, code, message, fields, error)

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = emit(LogLevel.ERROR, category, code, message, fields, error)

    /** Waits (bounded by [timeoutMs], docs/03 §5) until the queue is drained, then flushes sinks. */
    override fun flush(timeoutMs: Long) {
        idleLock.withLock {
            var remainingMs = timeoutMs.coerceAtLeast(0L)
            while (pending.get() > 0 && remainingMs > 0L) {
                val startedAt = System.nanoTime()
                try {
                    idle.await(remainingMs.coerceAtMost(FLUSH_POLL_MS), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                remainingMs -= (System.nanoTime() - startedAt) / 1_000_000L
            }
        }
        sinks.forEach { sink -> runCatching { sink.flush() } }
    }

    /** Flushes and stops accepting events (call on process shutdown / after a crash handler runs). */
    fun close(timeoutMs: Long = 500L) {
        flush(timeoutMs)
        closed.set(true)
    }

    private fun emit(
        level: LogLevel,
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        if (level < this.level) {
            filtered.incrementAndGet()
            return
        }
        val threadName = Thread.currentThread().name
        val sessionId = currentSession.get()
        val event = try {
            LogEvent(
                seq = sequence.incrementAndGet(),
                ts = clock.nowMs(),
                elapsedMs = uptime.readMs(),
                level = level,
                category = category,
                code = code,
                message = redact(message),
                fields = redact(fields),
                error = error,
                thread = threadName,
                screen = null,
                sessionId = sessionId,
            )
        } catch (t: Throwable) {
            // A failing clock / uptime / id source must not break the caller (we are on the hot path).
            envelopeFailures.incrementAndGet()
            return
        }
        if (!hasRealEnvelope(event)) {
            envelopeFailures.incrementAndGet()
            return
        }
        enqueue(event)
    }

    private fun hasRealEnvelope(event: LogEvent): Boolean =
        event.ts > 0L && event.elapsedMs >= 0L && event.thread.isNotBlank() && event.sessionId.isNotBlank()

    private fun enqueue(event: LogEvent) {
        if (closed.get()) return
        ensureWorker()
        if (offer(event)) return
        // Queue full (docs/03 §5): make room for an INFO+ event by evicting the oldest
        // VERBOSE/DEBUG event; a low-level arrival is simply dropped. Never block the caller.
        if (event.level >= LogLevel.INFO && evictLowPriority()) {
            if (offer(event)) return
        }
        dropped.incrementAndGet()
    }

    private fun offer(event: LogEvent): Boolean {
        pending.incrementAndGet()
        if (queue.offer(event)) return true
        pending.decrementAndGet()
        return false
    }

    private fun evictLowPriority(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.level < LogLevel.INFO) {
                // `queue.remove` reports whether the entry was still there — the dispatch thread
                // may have taken it between `next()` and `remove()`.
                if (!queue.remove(candidate)) return false
                pending.decrementAndGet()
                dropped.incrementAndGet()
                return true
            }
        }
        return false
    }

    private fun ensureWorker() {
        if (workerStarted.compareAndSet(false, true)) {
            executor.execute(::dispatchLoop)
        }
    }

    private fun dispatchLoop() {
        while (true) {
            val event = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (event == null) {
                if (closed.get()) return
                continue
            }
            dispatch(event)
            pending.decrementAndGet()
            signalIdle()
        }
    }

    private fun dispatch(event: LogEvent) {
        sinks.forEach { sink ->
            try {
                sink.write(event)
            } catch (t: Throwable) {
                // One broken sink must not starve the others (and must never reach the caller).
                sinkFailures.incrementAndGet()
            }
        }
    }

    private fun signalIdle() {
        if (pending.get() == 0) {
            idleLock.withLock { idle.signalAll() }
        }
    }

    private fun redact(event: LogEvent): LogEvent {
        if (redactor == null) return event
        return event.copy(message = redactor.redact(event.message), fields = redact(event.fields))
    }

    private fun redact(message: String): String = redactor?.redact(message) ?: message

    private fun redact(fields: Map<String, Any?>): Map<String, Any?> {
        if (redactor == null || fields.isEmpty()) return fields
        return fields.mapValues { (_, value) -> if (value is String) redactor.redact(value) else value }
    }

    companion object {
        /** docs/03 §5: bounded queue, capacity 1024. */
        const val DEFAULT_QUEUE_CAPACITY = 1024

        private const val POLL_TIMEOUT_MS = 100L
        private const val FLUSH_POLL_MS = 50L

        /** Initial session, replaced by `startSession(...)` for refreshes / playback. */
        private const val SESSION_PREFIX = "app"

        private fun newDispatchExecutor(): Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "iptv-log").apply { isDaemon = true }
            }
    }
}

/*
 * `LogLevel` is an enum, so `level < minLevel` already compares docs/03 §3.1 severity order
 * (VERBOSE < DEBUG < INFO < WARN < ERROR < FATAL) through the enum's own `Comparable`.
 */
