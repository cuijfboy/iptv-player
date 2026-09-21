package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.InfoBarState
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState
import org.junit.Test

/**
 * P1-7 item 2's metadata requirement ("元数据至少给频道名") plus the notification's action, as a table.
 *
 * The failure this catches is the realistic one: a later phase overwrites the title with a status line
 * ("正在起播…"), and the TV's now-playing area stops naming the channel.
 */
class PlaybackNotificationContentTest {

    @Test
    fun `the title is the channel name in every phase`() {
        val phases = listOf(
            PlaybackPhase.PREPARING,
            PlaybackPhase.BUFFERING,
            PlaybackPhase.PLAYING,
            PlaybackPhase.FAILOVER,
            PlaybackPhase.ERROR,
        )

        phases.forEach { phase ->
            val content = PlaybackNotificationContent.of(ui(phase = phase), EngineState.PLAYING)

            assertThat(content).isNotNull()
            assertThat(content!!.title).isEqualTo("CCTV-1")
        }
    }

    @Test
    fun `a channel that was never resolved gets no notification`() {
        val content = PlaybackNotificationContent.of(
            PlaybackUiState.EMPTY.copy(phase = PlaybackPhase.PREPARING),
            EngineState.PREPARING,
        )

        assertThat(content).isNull()
    }

    @Test
    fun `the failure text replaces the status line when the session failed`() {
        val content = PlaybackNotificationContent.of(
            ui(phase = PlaybackPhase.ERROR, errorText = "该频道暂时不可用"),
            EngineState.ERROR,
        )

        assertThat(content!!.text).isEqualTo("该频道暂时不可用")
        // A failure is not an ongoing state: the user can swipe the notification away, and the service
        // leaves the foreground (the notification is removed outright — see PlaybackServiceLifecycle).
        assertThat(content.ongoing).isFalse()
    }

    @Test
    fun `a failure without readable text still says something`() {
        val content = PlaybackNotificationContent.of(ui(phase = PlaybackPhase.ERROR), EngineState.ERROR)

        assertThat(content!!.text).isEqualTo(PlaybackNotificationContent.FAILED_TEXT)
    }

    @Test
    fun `the fail-over hint is what the notification shows while switching`() {
        val content = PlaybackNotificationContent.of(
            ui(phase = PlaybackPhase.FAILOVER, failoverHint = HINT_SWITCHING),
            EngineState.BUFFERING,
        )

        assertThat(content!!.text).isEqualTo(HINT_SWITCHING)
        assertThat(content.ongoing).isTrue()
    }

    @Test
    fun `playing offers pause, a paused stream offers play`() {
        val playing = PlaybackNotificationContent.of(
            ui(phase = PlaybackPhase.PLAYING, quality = "1080p H.264"),
            EngineState.PLAYING,
        )
        assertThat(playing!!.showPauseAction).isTrue()
        assertThat(playing.text).isEqualTo("1080p H.264")

        // ExoPlayer reports a user pause as READY + not playing: the same coarse state a
        // prepared-but-not-started stream has.
        val paused = PlaybackNotificationContent.of(ui(phase = PlaybackPhase.BUFFERING), EngineState.READY)
        assertThat(paused!!.showPauseAction).isFalse()
        assertThat(paused.text).contains(PlaybackNotificationContent.PAUSED_TEXT)
    }

    @Test
    fun `the channel is carried so tapping the notification re-opens it`() {
        val content = PlaybackNotificationContent.of(ui(phase = PlaybackPhase.PLAYING), EngineState.PLAYING)

        assertThat(content!!.channelId).isEqualTo(7L)
    }

    @Test
    fun `the placeholder keeps the service alive before a channel is known`() {
        // A foreground service must post something within seconds of being started.
        assertThat(PlaybackNotificationContent.PLACEHOLDER.ongoing).isTrue()
        assertThat(PlaybackNotificationContent.PLACEHOLDER.title)
            .isEqualTo(PlaybackNotificationContent.PLACEHOLDER_TITLE)
    }

    private fun ui(
        phase: PlaybackPhase,
        quality: String? = "1080p",
        failoverHint: String? = null,
        errorText: String? = null,
    ) = PlaybackUiState.EMPTY.copy(
        channelId = 7L,
        phase = phase,
        infoBar = InfoBarState(
            channelName = "CCTV-1",
            logoUrl = null,
            qualityLabel = quality,
            nowNext = null,
            failoverHint = failoverHint,
        ),
        lastError = if (errorText != null) AppError.timeout(EventCodes.PLAY_PREPARE_FAIL) else null,
        errorText = errorText,
    )
}
