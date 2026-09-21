package ilab.iptv.player.core.data.refresh

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationResult
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import kotlinx.coroutines.delay

/** A hand-driven clock so budget arithmetic is deterministic. */
class FakeClock(private var now: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

class RecordingLogger : Logger {
    val codes = mutableListOf<String>()

    override fun log(event: LogEvent) {
        codes += event.code
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        codes += code
    }

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        codes += code
    }

    override fun flush(timeoutMs: Long) = Unit

    fun count(code: String): Int = codes.count { it == code }
}

/** Deterministic session ids keep the assertion output stable. */
class FakeSessionIds : SessionIdFactory {
    private var n = 0
    override fun newId(prefix: String): String = "$prefix-${++n}"
}

/**
 * A source that returns a fixed outcome. When [advanceClockMs] is set it "spends" that much of the
 * budget, which is how the budget tests force the deadline to pass mid-run.
 */
class FakeSourceProvider(
    override val id: String,
    private val entries: List<RawEntry> = emptyList(),
    private val result: AppResult<List<RawEntry>>? = null,
    private val clock: FakeClock? = null,
    private val advanceClockMs: Long = 0,
    private val delayMs: Long = 0,
) : SourceProvider {
    override val label: String = id
    override val kind: SourceKind = SourceKind.M3U

    override suspend fun fetch(clock: Clock): AppResult<List<RawEntry>> {
        if (delayMs > 0) delay(delayMs)
        this.clock?.advance(advanceClockMs)
        return result ?: AppResult.Ok(entries)
    }
}

/** A validation chain of one link; [pass] fixes the verdict, so no HTTP is involved. */
class FakeStreamValidator(
    override val id: String = "fake.shallow",
    private val pass: Boolean = true,
    override val order: Int = 10,
    override val stage: ValidationStage = ValidationStage.SHALLOW,
) : StreamValidator {
    var calls: Int = 0
        private set

    override suspend fun validate(target: StreamTarget, ctx: ProbeContext): ValidationResult {
        calls++
        return if (pass) {
            ValidationResult(true, "ok", mapOf("status" to 200))
        } else {
            ValidationResult(false, "http 500", mapOf("failure" to "HTTP_SERVER", "status" to 500))
        }
    }
}

/** An entry with a unique URL per index, for building candidate sets. */
fun entry(index: Int, sourceId: String = "src"): RawEntry = RawEntry(
    name = "Channel $index",
    url = "http://stream.invalid/$index.m3u8",
    tvgId = null,
    groupTitle = "Group",
    sourceId = sourceId,
)

fun failure(code: String = EventCodes.SRC_FETCH_FAIL): AppError = AppError.timeout(code)
