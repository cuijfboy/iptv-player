package ilab.iptv.player.refresh

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshProgress
import kotlinx.coroutines.flow.Flow

/** A hand-driven clock, so "elapsed" and "delay" assertions never depend on wall time. */
class FakeClock(private var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

/** Records the event codes (and the fields of the ones a test needs), like the data-layer fake. */
class RecordingLogger : Logger {

    val codes = mutableListOf<String>()
    val fieldsByCode = mutableMapOf<String, MutableList<Map<String, Any?>>>()

    override fun log(event: LogEvent) {
        codes += event.code
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) = record(code, fields)

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) = record(code, fields)

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) = record(code, fields)

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = record(code, fields)

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = record(code, fields)

    override fun flush(timeoutMs: Long) = Unit

    fun count(code: String): Int = codes.count { it == code }

    fun fields(code: String): Map<String, Any?> = fieldsByCode[code]?.lastOrNull().orEmpty()

    private fun record(code: String, fields: Map<String, Any?>) {
        codes += code
        fieldsByCode.getOrPut(code) { mutableListOf() } += fields
    }
}

/** Books the specs the scheduler hands over, so the request shape is asserted without WorkManager. */
class RecordingWorkEnqueuer : WorkEnqueuer {

    val periodic = mutableListOf<RefreshWorkSpec>()
    val once = mutableListOf<RefreshWorkSpec>()

    /** NEW-004: the policy each `enqueueOnce` was given, in call order. */
    val oncePolicies = mutableListOf<RefreshEnqueuePolicy>()

    /** What [existing] answers; a test sets it to imitate what the queue already holds. */
    var queued: ExistingRefreshRun? = null

    override fun enqueuePeriodic(spec: RefreshWorkSpec) {
        periodic += spec
    }

    override fun enqueueOnce(spec: RefreshWorkSpec, policy: RefreshEnqueuePolicy) {
        once += spec
        oncePolicies += policy
    }

    override suspend fun existing(uniqueName: String): ExistingRefreshRun? = queued
}

/**
 * The NEW-004 mark in memory: what [RefreshWorker] would have set at the start of a run and cleared
 * at its end. `true` is the state a test seeds to imitate "the last run was killed by the process".
 */
class FakeRefreshRunLedger(var unconcluded: Boolean = false) : RefreshRunLedger {

    /** `started` / `concluded`, in call order. */
    val events = mutableListOf<String>()

    override fun markRunStarted() {
        unconcluded = true
        events += "started"
    }

    override fun markRunConcluded() {
        unconcluded = false
        events += "concluded"
    }

    override fun isRunUnconcluded(): Boolean = unconcluded
}

/** A pipeline whose frames and failure the test chooses; no Hilt, no network, no Room. */
class FakeRefreshRunner(
    private val frames: List<RefreshProgress> = emptyList(),
    private val error: Throwable? = null,
) : RefreshRunner {

    var calls: Int = 0
        private set

    var lastOptions: RefreshOptions? = null
        private set

    override fun run(options: RefreshOptions): Flow<RefreshProgress> {
        calls++
        lastOptions = options
        error?.let { throw it }
        return kotlinx.coroutines.flow.flow { frames.forEach { emit(it) } }
    }
}

/** One pipeline frame; the defaults satisfy `RefreshProgress`'s shape. */
fun frame(
    phase: ilab.iptv.player.core.model.RefreshPhase,
    done: Int = 0,
    total: Int = 0,
    ok: Int = 0,
    fail: Int = 0,
    elapsedMs: Long = 0,
    interrupted: ilab.iptv.player.core.model.RefreshInterruption? = null,
): RefreshProgress = RefreshProgress(
    phase = phase,
    done = done,
    total = total,
    okCount = ok,
    failCount = fail,
    elapsedMs = elapsedMs,
    interrupted = interrupted,
)
