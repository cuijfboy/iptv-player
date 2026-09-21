package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.PlaybackEvent
import ilab.iptv.player.core.model.PlaybackRequest
import ilab.iptv.player.core.model.Quality
import ilab.iptv.player.core.model.Stream
import org.junit.Test

/** docs/02 §7.3/§7.7 埋点: the codes, levels and fields the controller will emit. */
class PlaybackEventLoggerTest {

    private val logger = RecordingLogger()
    private val telemetry = PlaybackEventLogger(logger, sessionId = "play-test")

    @Test
    fun `prepare start carries the section 7_7 fields at INFO`() {
        telemetry.onPrepareStart("media3", request(), attempt = 2)

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.category).isEqualTo(LogCategory.PLAYER)
        assertThat(record.code).isEqualTo(EventCodes.PLAY_PREPARE_START)
        assertThat(record.fields["channelId"]).isEqualTo(7L)
        assertThat(record.fields["streamId"]).isEqualTo(42L)
        assertThat(record.fields["engine"]).isEqualTo("media3")
        assertThat(record.fields["attempt"]).isEqualTo(2)
    }

    @Test
    fun `first frame carries costMs and the audio path observation`() {
        telemetry.onFirstFrame(
            "media3",
            PlaybackEvent.FirstFrame(costMs = 1_234),
            PlaybackSnapshot("video/avc", "audio/ac3", 1_920, 1_080, AudioPath.PASSTHROUGH_HAL),
        )

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.code).isEqualTo(EventCodes.PLAY_FIRST_FRAME)
        assertThat(record.fields["costMs"]).isEqualTo(1_234L)
        assertThat(record.fields["vcodec"]).isEqualTo("video/avc")
        assertThat(record.fields["acodec"]).isEqualTo("audio/ac3")
        assertThat(record.fields["w"]).isEqualTo(1_920)
        assertThat(record.fields["h"]).isEqualTo(1_080)
        assertThat(record.fields["audioPath"]).isEqualTo("PASSTHROUGH_HAL")
    }

    @Test
    fun `prepare fail is a WARN with the failure class`() {
        val error = AppError(EventCodes.PLAY_PREPARE_FAIL, FailureClass.HTTP_CLIENT, retryable = false, httpStatus = 404)
        telemetry.onPrepareFail("media3", error, attempt = 1)

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.WARN)
        assertThat(record.code).isEqualTo(EventCodes.PLAY_PREPARE_FAIL)
        assertThat(record.fields["failure"]).isEqualTo("HTTP_CLIENT")
        assertThat(record.fields["httpStatus"]).isEqualTo(404)
    }

    @Test
    fun `engine lifecycle is DEBUG`() {
        telemetry.onEngineInit("media3", setOf(EngineCapability.HLS))
        telemetry.onEngineRelease("media3")

        assertThat(logger.records.map { it.code })
            .containsExactly(EventCodes.PLAY_ENGINE_INIT, EventCodes.PLAY_ENGINE_RELEASE)
            .inOrder()
        assertThat(logger.records.map { it.level }).containsExactly(LogLevel.DEBUG, LogLevel.DEBUG)
    }

    @Test
    fun `stall is a WARN`() {
        telemetry.onStalled("media3", PlaybackEvent.Stalled(stalledMs = 8_500, positionMs = 12_000))

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.WARN)
        assertThat(record.code).isEqualTo(EventCodes.PLAY_STALL)
        assertThat(record.fields["stalledMs"]).isEqualTo(8_500L)
    }

    @Test
    fun `onEvent routes coded events and reports what it logged`() {
        val snapshot = PlaybackSnapshot.EMPTY
        assertThat(telemetry.onEvent("media3", PlaybackEvent.FirstFrame(10), snapshot)).isTrue()
        assertThat(telemetry.onEvent("media3", PlaybackEvent.Stalled(1, 2), snapshot)).isTrue()
        assertThat(telemetry.onEvent("media3", PlaybackEvent.Capabilities(setOf(EngineCapability.HLS)), snapshot)).isTrue()
    }

    @Test
    fun `onEvent passes through the events the controller owns`() {
        val snapshot = PlaybackSnapshot.EMPTY
        assertThat(
            telemetry.onEvent("media3", PlaybackEvent.AudioTracks(emptyList(), null), snapshot),
        ).isFalse()
        // A post-first-frame error is a session failure (P1-6 -> PLAY_FAILOVER), never a prepare code.
        assertThat(
            telemetry.onEvent(
                "media3",
                PlaybackEvent.Error(AppError("x", FailureClass.UNKNOWN, false), fatal = true),
                snapshot,
            ),
        ).isFalse()
    }

    @Test
    fun `every logged code is registered in the event-code registry`() {
        telemetry.onPrepareStart("media3", request(), attempt = 1)
        telemetry.onFirstFrame("media3", PlaybackEvent.FirstFrame(1), PlaybackSnapshot.EMPTY)
        telemetry.onPrepareFail("media3", AppError("x", FailureClass.UNKNOWN, false), attempt = 1)
        telemetry.onStalled("media3", PlaybackEvent.Stalled(1, 1))
        telemetry.onEngineInit("media3", emptySet())
        telemetry.onEngineRelease("media3")

        assertThat(logger.records).isNotEmpty()
        logger.records.forEach { record ->
            assertThat(EventCodes.isRegistered(record.code)).isTrue()
        }
    }

    private fun request() = PlaybackRequest(
        channelId = 7L,
        stream = Stream(
            id = 42L,
            channelId = 7L,
            url = "https://example.invalid/live.m3u8",
            userAgent = null,
            referrer = null,
            sourceId = "src",
            quality = Quality.FHD_1080,
            videoCodec = "h264",
            audioCodec = "aac",
            width = 1_920,
            height = 1_080,
            score = 90,
            priority = 0,
            lastOkAtMs = null,
            lastCheckAtMs = null,
            failCount = 0,
            lastError = null,
            disabled = false,
        ),
        sessionId = "play-test",
    )

    private data class Record(val level: LogLevel, val category: LogCategory, val code: String, val fields: Map<String, Any?>)

    private class RecordingLogger : Logger {
        val records = mutableListOf<Record>()

        override fun log(event: LogEvent) {
            records += Record(event.level, event.category, event.code, event.fields)
        }

        override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            add(LogLevel.VERBOSE, category, code, fields)

        override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            add(LogLevel.DEBUG, category, code, fields)

        override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            add(LogLevel.INFO, category, code, fields)

        override fun w(category: LogCategory, code: String, message: String, fields: Map<String, Any?>, error: Throwable?) =
            add(LogLevel.WARN, category, code, fields)

        override fun e(category: LogCategory, code: String, message: String, fields: Map<String, Any?>, error: Throwable?) =
            add(LogLevel.ERROR, category, code, fields)

        override fun flush(timeoutMs: Long) = Unit

        fun single(): Record = records.single()

        private fun add(level: LogLevel, category: LogCategory, code: String, fields: Map<String, Any?>) {
            records += Record(level, category, code, fields)
        }
    }
}
