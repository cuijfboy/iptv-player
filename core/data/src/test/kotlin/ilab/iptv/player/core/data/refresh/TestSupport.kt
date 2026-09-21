package ilab.iptv.player.core.data.refresh

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.ChannelWithStreams
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationResult
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val evidence: Map<String, Any?>? = null,
    private val clock: FakeClock? = null,
    private val advanceClockMs: Long = 0,
) : StreamValidator {
    var calls: Int = 0
        private set

    override suspend fun validate(target: StreamTarget, ctx: ProbeContext): ValidationResult {
        calls++
        clock?.advance(advanceClockMs)
        return if (pass) {
            ValidationResult(true, "ok", evidence ?: mapOf("status" to 200))
        } else {
            ValidationResult(false, "http 500", mapOf("failure" to "HTTP_SERVER", "status" to 500))
        }
    }
}

/**
 * A DEEP-stage validator whose verdict and evidence a test controls per URL, so scoring and
 * selection can be driven with no network and no engine.
 */
class FakeDeepValidator(
    override val id: String = "fake.deep",
    override val order: Int = 20,
    private val verdict: (String) -> ValidationResult,
) : StreamValidator {

    override val stage: ValidationStage = ValidationStage.DEEP

    val calls = mutableListOf<String>()

    override suspend fun validate(target: StreamTarget, ctx: ProbeContext): ValidationResult {
        calls += target.url
        return verdict(target.url)
    }

    companion object {
        /**
         * A passing probe reporting 1080p H.264/AAC plus a 2xx segment. Any URL containing "dead"
         * fails instead, which is how a test builds a half-broken channel.
         */
        fun passing(
            videoCodec: String = "video/avc",
            audioCodec: String = "audio/mp4a-latm",
            width: Int = 1920,
            height: Int = 1080,
        ): FakeDeepValidator = FakeDeepValidator { url ->
            if (url.contains("dead")) {
                ValidationResult(false, "segment http 503", mapOf("failure" to "HTTP_SERVER"))
            } else {
                ValidationResult(
                    passed = true,
                    detail = "deep ok",
                    evidence = mapOf(
                        "vcodec" to videoCodec,
                        "acodec" to audioCodec,
                        "w" to width,
                        "h" to height,
                        "segStatus" to 200,
                        "segBytes" to 1880,
                    ),
                )
            }
        }
    }
}

/**
 * `ChannelRepository` over the in-memory [ChannelStore]. Only `get` is exercised by the refresh
 * pipeline (the on-demand single-channel path); the rest are the frozen port's no-ops.
 */
class FakeChannelRepository(private val store: ChannelStore) : ChannelRepository {

    override fun observe(filter: ChannelFilter): Flow<List<ChannelWithStreams>> =
        store.channels.map { channels -> channels.map { ChannelWithStreams(it, streamsOf(it.id)) } }

    override suspend fun get(channelId: Long): ChannelWithStreams? =
        store.channels.value.firstOrNull { it.id == channelId }?.let { ChannelWithStreams(it, streamsOf(it.id)) }

    override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit

    override suspend fun setHidden(channelId: Long, hidden: Boolean) = Unit

    override suspend fun reorder(channelId: Long, newIndex: Int) = Unit

    override suspend fun setChannelNo(channelId: Long, channelNo: Int?) = Unit

    override suspend fun setEpgBinding(channelId: Long, epgChannelId: String?, match: EpgMatchType) = Unit

    override suspend fun countByGroup(): Map<ChannelGroup, Int> = emptyMap()

    private fun streamsOf(channelId: Long): List<Stream> =
        store.snapshotStreams().filter { it.channelId == channelId }
}

/** A channel row for the on-demand path's seed. */
fun channelRow(id: Long, name: String, streamCount: Int): Channel = Channel(
    id = id,
    name = name,
    tvgId = null,
    group = ChannelGroup.OTHER,
    logoUrl = null,
    channelNo = null,
    favorite = false,
    hidden = false,
    sortOrder = 0,
    epgChannelId = null,
    epgMatch = EpgMatchType.NONE,
    streamCount = streamCount,
)

/** One persisted stream row; `lastOkAtMs`/`failCount` are what the freshness rules read. */
fun streamRow(
    id: Long,
    channelId: Long,
    url: String,
    score: Int = 0,
    lastOkAtMs: Long? = null,
    lastCheckAtMs: Long? = null,
    failCount: Int = 0,
    disabled: Boolean = false,
): Stream = Stream(
    id = id,
    channelId = channelId,
    url = url,
    urlHash = "hash-$id",
    userAgent = null,
    referrer = null,
    sourceId = "seed",
    quality = null,
    videoCodec = null,
    audioCodec = null,
    width = 0,
    height = 0,
    score = score,
    priority = 0,
    lastOkAtMs = lastOkAtMs,
    lastCheckAtMs = lastCheckAtMs,
    failCount = failCount,
    lastError = null,
    disabled = disabled,
)

/** A `RawEntry` under a chosen channel name, so several entries can share one channel. */
fun namedEntry(name: String, url: String, sourceId: String = "src"): RawEntry = RawEntry(
    name = name,
    url = url,
    tvgId = null,
    groupTitle = "Group",
    sourceId = sourceId,
)

/** An entry with a unique URL per index, for building candidate sets. */
fun entry(index: Int, sourceId: String = "src"): RawEntry = RawEntry(
    name = "Channel $index",
    url = "http://stream.invalid/$index.m3u8",
    tvgId = null,
    groupTitle = "Group",
    sourceId = sourceId,
)

fun failure(code: String = EventCodes.SRC_FETCH_FAIL): AppError = AppError.timeout(code)
