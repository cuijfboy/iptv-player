package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Redactor
import ilab.iptv.player.core.common.SessionIdFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Test

/**
 * Pure JVM tests for the log pipeline — no Android, no device (the whole point of keeping the
 * facade in `:core:common` and the bus free of Android types). Covers the three behaviours the
 * P0-6 exit needs: level filtering, envelope stamping (never faked), and multi-sink fan-out.
 */
class LogBusTest {

    private val executors = mutableListOf<ExecutorService>()

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
    }

    // --- level filtering -------------------------------------------------------------------

    @Test
    fun `drops events below the minimum level and counts them`() {
        val sink = RecordingSink()
        val clock = FakeClock()
        val bus = newBus(setOf(sink), clock)
        bus.minLevel = LogLevel.INFO

        bus.v(LogCategory.APP, EventCodes.APP_START, "verbose")
        bus.d(LogCategory.APP, EventCodes.APP_START, "debug")
        bus.i(LogCategory.APP, EventCodes.APP_START, "info")
        bus.flush(TIMEOUT_MS)

        assertThat(sink.messages()).containsExactly("info")
        assertThat(bus.filteredCount).isEqualTo(2)
        assertThat(bus.droppedCount).isEqualTo(0)
    }

    @Test
    fun `level is switchable at runtime`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock())

        bus.d(LogCategory.UI, EventCodes.UI_SCREEN_OPEN, "hidden by default")
        bus.minLevel = LogLevel.DEBUG
        bus.d(LogCategory.UI, EventCodes.UI_SCREEN_OPEN, "visible after switch")
        bus.flush(TIMEOUT_MS)

        assertThat(sink.messages()).containsExactly("visible after switch")
    }

    // --- envelope (CR-05: real values or no event) -----------------------------------------

    @Test
    fun `stamps the envelope from the clock uptime session and current thread`() {
        val sink = RecordingSink()
        val clock = FakeClock(now = 1_700_000_000_123L, uptime = 4_242L)
        val bus = newBus(setOf(sink), clock)

        bus.i(LogCategory.APP, EventCodes.APP_START, "app started", mapOf("sdk" to 31))
        bus.flush(TIMEOUT_MS)

        val event = sink.events.single()
        assertThat(event.seq).isEqualTo(1L)
        assertThat(event.ts).isEqualTo(1_700_000_000_123L)
        assertThat(event.elapsedMs).isEqualTo(4_242L)
        assertThat(event.thread).isEqualTo(Thread.currentThread().name)
        assertThat(event.thread).isNotEmpty()
        assertThat(event.sessionId).startsWith("app-")
        assertThat(event.code).isEqualTo(EventCodes.APP_START)
        assertThat(event.fields).containsEntry("sdk", 31)
        assertThat(event.level).isEqualTo(LogLevel.INFO)
        assertThat(event.error).isNull()
    }

    @Test
    fun `sequence keeps increasing`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock())

        repeat(3) { bus.i(LogCategory.APP, EventCodes.APP_START, "n$it") }
        bus.flush(TIMEOUT_MS)

        assertThat(sink.events.map { it.seq }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `drops the event instead of faking a missing envelope value`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock(now = 0L))

        bus.i(LogCategory.APP, EventCodes.APP_START, "no wall clock")
        bus.flush(TIMEOUT_MS)

        assertThat(sink.events).isEmpty()
        assertThat(bus.envelopeFailureCount).isEqualTo(1)
        assertThat(bus.droppedCount).isEqualTo(0)
    }

    @Test
    fun `a failing envelope source never reaches the caller`() {
        val sink = RecordingSink()
        val brokenClock = object : Clock, UptimeMs {
            override fun nowMs(): Long = throw IllegalStateException("no clock")
            override fun readMs(): Long = throw IllegalStateException("no uptime")
        }
        val bus = LogBus(
            sinks = setOf(sink),
            clock = brokenClock,
            uptime = brokenClock,
            sessionIds = FakeSessionIds(),
            executor = realExecutor(),
        )

        bus.e(LogCategory.DB, EventCodes.DB_FAIL, "insert failed", error = IllegalStateException("boom"))
        bus.flush(TIMEOUT_MS)

        assertThat(sink.events).isEmpty()
        assertThat(bus.envelopeFailureCount).isEqualTo(1)
    }

    // --- session correlation ---------------------------------------------------------------

    @Test
    fun `startSession correlates the following events`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock())

        val sessionId = bus.startSession("play")

        assertThat(sessionId).startsWith("play-")
        assertThat(bus.currentSessionId()).isEqualTo(sessionId)
        bus.i(LogCategory.PLAYER, EventCodes.PLAY_PREPARE_START, "prepare")
        bus.flush(TIMEOUT_MS)
        assertThat(sink.events.single().sessionId).isEqualTo(sessionId)
    }

    // --- fan-out ---------------------------------------------------------------------------

    @Test
    fun `fans one event out to every sink`() {
        val ring = MemoryRingSink(capacity = 10)
        val recording = RecordingSink()
        val bus = newBus(setOf(ring, recording), FakeClock())

        bus.w(LogCategory.NET, EventCodes.NET_REQ_FAIL, "network down", mapOf("host" to "h"))
        bus.flush(TIMEOUT_MS)

        assertThat(recording.events).hasSize(1)
        assertThat(ring.snapshot()).hasSize(1)
        assertThat(ring.snapshot().single().eventId()).isEqualTo(recording.events.single().eventId())
    }

    @Test
    fun `one broken sink does not stop the others`() {
        val broken = object : LogSink {
            override val id = "broken"
            override fun write(event: LogEvent) = throw IllegalStateException("sink exploded")
            override fun flush() = Unit
        }
        val healthy = RecordingSink()
        val bus = newBus(setOf(broken, healthy), FakeClock())

        bus.i(LogCategory.APP, EventCodes.APP_START, "still delivered")
        bus.flush(TIMEOUT_MS)

        assertThat(healthy.messages()).containsExactly("still delivered")
        assertThat(bus.sinkFailureCount).isEqualTo(1)
    }

    // --- back pressure (docs 03 section 5) ------------------------------------------------

    @Test
    fun `full queue drops a low level arrival`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock(), queueCapacity = 2, executor = ManualExecutor())
        bus.minLevel = LogLevel.DEBUG

        bus.d(LogCategory.APP, EventCodes.APP_START, "d1")
        bus.d(LogCategory.APP, EventCodes.APP_START, "d2")
        bus.d(LogCategory.APP, EventCodes.APP_START, "d3")

        assertThat(bus.droppedCount).isEqualTo(1)
        assertThat(bus.pendingCount).isEqualTo(2)
    }

    @Test
    fun `full queue evicts the oldest low level event to keep an info event`() {
        val sink = RecordingSink()
        val executor = ManualExecutor()
        val bus = newBus(setOf(sink), FakeClock(), queueCapacity = 2, executor = executor)
        bus.minLevel = LogLevel.DEBUG
        bus.d(LogCategory.APP, EventCodes.APP_START, "d1")
        bus.d(LogCategory.APP, EventCodes.APP_START, "d2")

        bus.i(LogCategory.APP, EventCodes.APP_START, "i1")

        assertThat(bus.droppedCount).isEqualTo(1)
        bus.close(FLUSH_TIMEOUT_MS)
        executor.runAll() // the queue is closed now, so the dispatch loop drains and returns

        assertThat(sink.messages()).containsExactly("d2", "i1").inOrder()
        assertThat(bus.pendingCount).isEqualTo(0)
    }

    @Test
    fun `flush waits until the queue is drained`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock())

        repeat(50) { bus.i(LogCategory.APP, EventCodes.APP_START, "n$it") }
        bus.flush(TIMEOUT_MS)

        assertThat(sink.events).hasSize(50)
        assertThat(bus.pendingCount).isEqualTo(0)
    }

    // --- caller-built events and redaction --------------------------------------------------

    @Test
    fun `log honours the level filter and redacts`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock(), redactor = UrlRedactor())

        bus.log(event(LogLevel.DEBUG, "http://cdn/x.m3u8?token=secret"))
        bus.log(event(LogLevel.INFO, "http://cdn/x.m3u8?token=secret"))
        bus.flush(TIMEOUT_MS)

        assertThat(sink.events).hasSize(1)
        // docs/03 §11: the secret is masked *and* the path is replaced by its hash, before the event
        // reaches any sink (the bus is the single redaction entry).
        assertThat(sink.events.single().message).matches("""http://cdn/[0-9a-f]{8}\?token=\*\*\*""")
        assertThat(bus.filteredCount).isEqualTo(1)
    }

    @Test
    fun `redacts the message and string fields of shortcut events`() {
        val sink = RecordingSink()
        val bus = newBus(setOf(sink), FakeClock(), redactor = UrlRedactor())

        bus.w(
            LogCategory.NET,
            EventCodes.NET_REQ_FAIL,
            "failed http://cdn/x?token=abc&id=3",
            mapOf("url" to "http://cdn/y?secret=zzz", "status" to 403),
        )
        bus.flush(TIMEOUT_MS)

        val event = sink.events.single()
        assertThat(event.message).matches("""failed http://cdn/[0-9a-f]{8}\?token=\*\*\*&id=3""")
        assertThat(event.fields["url"] as String).matches("""http://cdn/[0-9a-f]{8}\?secret=\*\*\*""")
        assertThat(event.fields).containsEntry("status", 403)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun newBus(
        sinks: Set<LogSink>,
        clock: FakeClock,
        redactor: Redactor? = null,
        queueCapacity: Int = LogBus.DEFAULT_QUEUE_CAPACITY,
        executor: Executor = realExecutor(),
    ): LogBus = LogBus(
        sinks = sinks,
        clock = clock,
        uptime = clock,
        sessionIds = FakeSessionIds(),
        redactor = redactor,
        queueCapacity = queueCapacity,
        executor = executor,
    )

    private fun realExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-log").apply { isDaemon = true }
        }.also { executors += it }

    private fun event(level: LogLevel, message: String): LogEvent = LogEvent(
        seq = 1L,
        ts = 1_700_000_000_000L,
        elapsedMs = 10L,
        level = level,
        category = LogCategory.NET,
        code = EventCodes.NET_REQ_FAIL,
        message = message,
        thread = "caller",
        sessionId = "refresh-0000",
    )

    private fun LogEvent.eventId(): Long = seq

    private class RecordingSink(override val id: String = "recording") : LogSink {
        val events = CopyOnWriteArrayList<LogEvent>()

        override fun write(event: LogEvent) {
            events += event
        }

        override fun flush() = Unit

        fun messages(): List<String> = events.map { it.message }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class FakeClock(
        private val now: Long = 1_700_000_000_000L,
        private val uptime: Long = 1_000L,
    ) : Clock, UptimeMs {
        override fun nowMs(): Long = now
        override fun readMs(): Long = uptime
    }

    private class FakeSessionIds : SessionIdFactory {
        private var counter = 0
        override fun newId(prefix: String): String = "$prefix-${(counter++).toString().padStart(4, '0')}"
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val FLUSH_TIMEOUT_MS = 50L
    }
}
