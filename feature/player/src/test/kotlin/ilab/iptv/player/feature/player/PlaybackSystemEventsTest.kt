package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.player.AudioFocusPolicy
import ilab.iptv.player.core.player.AudioFocusEvent
import org.junit.Test

/**
 * docs/03 §3.3.1 key-path self-check (P1-7 three links + the code card's four codes).
 *
 * The point is not the emitter's shape — it is that these four codes are **actually sent** on the
 * service start/stop, the audio-focus change and the network-restore retry, with the field names the
 * troubleshooting manual reads. If a later change silently drops one, this fails instead of the code
 * quietly disappearing from the log.
 */
class PlaybackSystemEventsTest {

    private val logger = RecordingLogger()
    private val events = PlaybackSystemEvents(logger)

    @Test
    fun `the four key-path codes are registered`() {
        assertThat(EventCodes.ALL)
            .containsAtLeast(
                EventCodes.SERVICE_PLAYBACK_START,
                EventCodes.SERVICE_PLAYBACK_STOP,
                EventCodes.PLAY_FOCUS_CHANGE,
                EventCodes.PLAY_NET_RETRY,
            )
    }

    @Test
    fun `a foreground start emits SERVICE_PLAYBACK_START with the channel and result ok`() {
        events.playbackServiceStarted(channelId = 7L, streamId = 42L, phase = "PLAYING")

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.category).isEqualTo(LogCategory.SERVICE)
        assertThat(record.code).isEqualTo(EventCodes.SERVICE_PLAYBACK_START)
        assertThat(record.fields["channelId"]).isEqualTo(7L)
        assertThat(record.fields["streamId"]).isEqualTo(42L)
        assertThat(record.fields["phase"]).isEqualTo("PLAYING")
        assertThat(record.fields["result"]).isEqualTo("ok")
    }

    @Test
    fun `a rejected foreground start emits the same code with result failed and the error`() {
        val denied = IllegalStateException("startForeground from background")

        events.playbackServiceStartFailed(phase = "PLAYING", error = denied)

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.ERROR)
        assertThat(record.code).isEqualTo(EventCodes.SERVICE_PLAYBACK_START)
        assertThat(record.fields["result"]).isEqualTo("failed")
        assertThat(record.fields["phase"]).isEqualTo("PLAYING")
        assertThat(record.error).isSameInstanceAs(denied)
    }

    @Test
    fun `the service stop emits SERVICE_PLAYBACK_STOP with the reason`() {
        events.playbackServiceStopped(reason = "phase-idle", channelId = 7L, streamId = 42L)

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.category).isEqualTo(LogCategory.SERVICE)
        assertThat(record.code).isEqualTo(EventCodes.SERVICE_PLAYBACK_STOP)
        assertThat(record.fields["reason"]).isEqualTo("phase-idle")
        assertThat(record.fields["channelId"]).isEqualTo(7L)
        assertThat(record.fields["streamId"]).isEqualTo(42L)
    }

    @Test
    fun `a focus loss emits PLAY_FOCUS_CHANGE with the reason and what the policy decided`() {
        val policy = AudioFocusPolicy()

        val decision = policy.onEvent(AudioFocusEvent.LOSS)
        events.focusChanged(AudioFocusEvent.LOSS, decision)

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.category).isEqualTo(LogCategory.PLAYER)
        assertThat(record.code).isEqualTo(EventCodes.PLAY_FOCUS_CHANGE)
        assertThat(record.fields["reason"]).isEqualTo("LOSS")
        assertThat(record.fields["pause"]).isEqualTo(true)
        assertThat(record.fields["abandonFocus"]).isEqualTo(true)
    }

    @Test
    fun `a focus gain after a transient loss emits PLAY_FOCUS_CHANGE with resume`() {
        val policy = AudioFocusPolicy()
        policy.onEvent(AudioFocusEvent.LOSS_TRANSIENT)

        val decision = policy.onEvent(AudioFocusEvent.GAIN)
        events.focusChanged(AudioFocusEvent.GAIN, decision)

        val record = logger.single()
        assertThat(record.code).isEqualTo(EventCodes.PLAY_FOCUS_CHANGE)
        assertThat(record.fields["reason"]).isEqualTo("GAIN")
        assertThat(record.fields["resume"]).isEqualTo(true)
        assertThat(record.fields["restoreVolume"]).isEqualTo(true)
    }

    @Test
    fun `a network restore retry emits PLAY_NET_RETRY through the real wire with the attempt`() {
        val availability = FakeNetworkAvailability()
        var failing = true
        val wire = NetworkRetryWire(
            availability = availability,
            inFailureState = { failing },
            retry = availability::retry,
            events = events,
            channelId = { 7L },
        )
        wire.attach()

        // The availability delivered at registration is not an outage, so it must not log anything.
        availability.available()
        assertThat(logger.records).isEmpty()

        availability.lost()
        availability.available()

        val record = logger.single()
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.category).isEqualTo(LogCategory.PLAYER)
        assertThat(record.code).isEqualTo(EventCodes.PLAY_NET_RETRY)
        assertThat(record.fields["attempt"]).isEqualTo(1)
        assertThat(record.fields["channelId"]).isEqualTo(7L)
        assertThat(availability.retries).isEqualTo(1)
    }

    @Test
    fun `every emitted code is registered in the event-code registry`() {
        events.playbackServiceStarted(1L, 2L, "PLAYING")
        events.playbackServiceStartFailed("PLAYING", IllegalStateException("x"))
        events.playbackServiceStopped("phase-idle", 1L, 2L)
        events.focusChanged(AudioFocusEvent.LOSS, AudioFocusPolicy().onEvent(AudioFocusEvent.LOSS))
        events.networkRetry(1, 1L)

        assertThat(logger.records).hasSize(5)
        logger.records.forEach { record ->
            assertThat(EventCodes.isRegistered(record.code)).isTrue()
        }
    }

    private data class Record(
        val level: LogLevel,
        val category: LogCategory,
        val code: String,
        val fields: Map<String, Any?>,
        val error: Throwable?,
    )

    private class RecordingLogger : Logger {
        val records = mutableListOf<Record>()

        override fun log(event: LogEvent) {
            records += Record(event.level, event.category, event.code, event.fields, event.error)
        }

        override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            add(LogLevel.VERBOSE, category, code, fields, null)

        override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            add(LogLevel.DEBUG, category, code, fields, null)

        override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            add(LogLevel.INFO, category, code, fields, null)

        override fun w(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = add(LogLevel.WARN, category, code, fields, error)

        override fun e(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = add(LogLevel.ERROR, category, code, fields, error)

        override fun flush(timeoutMs: Long) = Unit

        fun single(): Record = records.single()

        private fun add(
            level: LogLevel,
            category: LogCategory,
            code: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) {
            records += Record(level, category, code, fields, error)
        }
    }

    /** Stands in for `ConnectivityManager`: registration delivers availability, then the test drives. */
    private class FakeNetworkAvailability : NetworkAvailability {
        var retries = 0
        private var onLostAction: (() -> Unit)? = null
        private var onAvailableAction: (() -> Unit)? = null

        override fun start(onLost: () -> Unit, onAvailable: () -> Unit) {
            onLostAction = onLost
            onAvailableAction = onAvailable
            onAvailable()
        }

        override fun stop() = Unit

        fun lost() = onLostAction?.invoke() ?: error("listener not registered")

        fun available() = onAvailableAction?.invoke() ?: error("listener not registered")

        fun retry() {
            retries += 1
        }
    }
}
