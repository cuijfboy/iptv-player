package ilab.iptv.player.core.epg

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.network.HttpBody
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.network.StreamingHttpFetcher
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A clock a test drives by hand (docs/02 §4.1 `Clock`). */
class FakeClock(private var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }

    fun set(nowMs: Long) {
        now = nowMs
    }
}

/** Captures event codes and fields so a test can assert what the pipeline would log. */
class RecordingLogger : Logger {
    val codes = mutableListOf<String>()
    val events = mutableListOf<LogEvent>()

    fun count(code: String): Int = codes.count { it == code }

    private fun record(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
        events += LogEvent(
            seq = events.size.toLong(),
            ts = 0,
            elapsedMs = 0,
            level = ilab.iptv.player.core.common.LogLevel.DEBUG,
            category = category,
            code = code,
            message = message,
            fields = fields,
            thread = "test",
            sessionId = "test",
        )
    }

    override fun log(event: LogEvent) {
        codes += event.code
        events += event
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        record(category, code, message, fields)

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        record(category, code, message, fields)

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
        record(category, code, message, fields)

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = record(category, code, message, fields)

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = record(category, code, message, fields)

    override fun flush(timeoutMs: Long) = Unit
}

/**
 * A [StreamingHttpFetcher] that answers from memory — no socket, so every provider test is
 * CI-runnable and the job's rule "HTTP 用假体，不真连网" holds. [attempts] counts calls so a test can
 * assert the provider does **not** add a retry loop of its own on top of the transport's.
 */
class FakeStreamingFetcher(
    private var answer: (HttpRequest) -> AppResult<HttpBody>,
) : StreamingHttpFetcher {

    var attempts: Int = 0
        private set

    val requests = mutableListOf<HttpRequest>()

    fun answerWith(result: AppResult<HttpBody>) {
        answer = { result }
    }

    override suspend fun open(request: HttpRequest): AppResult<HttpBody> {
        attempts++
        requests += request
        return answer(request)
    }

    companion object {
        fun body(
            text: String,
            status: Int = 200,
            contentLength: Long? = text.toByteArray().size.toLong(),
            contentType: String? = "application/xml",
        ): AppResult<HttpBody> = AppResult.Ok(
            HttpBody(
                status = status,
                contentType = contentType,
                contentLength = contentLength,
                stream = ByteArrayInputStream(text.toByteArray()),
            ),
        )

        fun bytes(payload: ByteArray, contentLength: Long? = payload.size.toLong()): AppResult<HttpBody> =
            AppResult.Ok(
                HttpBody(
                    status = 200,
                    contentType = "application/gzip",
                    contentLength = contentLength,
                    stream = ByteArrayInputStream(payload),
                ),
            )

        fun failure(error: AppError): AppResult<HttpBody> = AppResult.Err(error)

        fun httpFailure(status: Int): AppResult<HttpBody> =
            AppResult.Err(AppError.http(status, EventCodes.NET_REQ_FAIL))
    }
}

/** Reads an [InputStream] fully as text (the tests' small fixtures only — never a real guide). */
fun InputStream.readAllText(): String = reader(Charsets.UTF_8).readText()

/** A minimal, valid `Channel` for matcher tests; every field a test does not care about has a default. */
fun channel(
    id: Long,
    name: String,
    tvgId: String? = null,
    group: ChannelGroup = ChannelGroup.CCTV,
    epgChannelId: String? = null,
    epgMatch: EpgMatchType = EpgMatchType.NONE,
    nameKey: String = EpgNameKey.key(name),
): Channel = Channel(
    id = id,
    name = name,
    nameKey = nameKey,
    tvgId = tvgId,
    group = group,
    groupKey = group.key,
    groupTitle = group.key,
    logoUrl = null,
    channelNo = null,
    favorite = false,
    hidden = false,
    sortOrder = 0,
    epgChannelId = epgChannelId,
    epgMatch = epgMatch,
    streamCount = 1,
)
