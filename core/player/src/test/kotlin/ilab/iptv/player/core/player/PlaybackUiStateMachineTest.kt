package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.AudioTrackInfo
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.InfoBarState
import ilab.iptv.player.core.model.NowNext
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PreparedMedia
import ilab.iptv.player.core.model.Programme
import org.junit.Test

/**
 * Pins the frozen phase mapping of docs/02 §4.5 C1 and the P1-4 additions on top of it (readable
 * failure text, quality label, info-bar state) so a drifting edit shows up as a failure instead of
 * as a wrong spinner on the TV.
 */
class PlaybackUiStateMachineTest {

    private val machine = PlaybackUiStateMachine()

    @Test
    fun `engine state maps exactly onto the frozen phase table`() {
        val expected = mapOf(
            EngineState.IDLE to PlaybackPhase.IDLE,
            EngineState.PREPARING to PlaybackPhase.PREPARING,
            EngineState.READY to PlaybackPhase.BUFFERING,
            EngineState.BUFFERING to PlaybackPhase.BUFFERING,
            EngineState.PLAYING to PlaybackPhase.PLAYING,
            EngineState.ENDED to PlaybackPhase.STOPPED,
            EngineState.ERROR to PlaybackPhase.ERROR,
            EngineState.RELEASED to PlaybackPhase.STOPPED,
        )

        expected.forEach { (engineState, phase) ->
            assertThat(machine.onEngineState(engineState).phase).isEqualTo(phase)
        }
    }

    @Test
    fun `watching a channel clears the previous failure and carries the info bar`() {
        machine.onError(error(FailureClass.NET_UNREACHABLE, retryable = true))

        val state = machine.onWatchStarted(channelId = 7, streamId = 70, infoBar = infoBar())

        assertThat(state.phase).isEqualTo(PlaybackPhase.PREPARING)
        assertThat(state.channelId).isEqualTo(7)
        assertThat(state.activeStreamId).isEqualTo(70)
        assertThat(state.lastError).isNull()
        assertThat(state.errorText).isNull()
        assertThat(state.infoBar?.channelName).isEqualTo("CCTV1")
    }

    @Test
    fun `a failure is readable and recoverable by retry`() {
        val failed = machine.onError(error(FailureClass.HTTP_CLIENT, retryable = false, status = 404))

        assertThat(failed.phase).isEqualTo(PlaybackPhase.ERROR)
        assertThat(failed.errorText).contains("HTTP 404")

        val retrying = machine.onRetryRequested()
        assertThat(retrying.phase).isEqualTo(PlaybackPhase.PREPARING)
        assertThat(retrying.errorText).isNull()
        assertThat(retrying.lastError).isNull()
    }

    @Test
    fun `prepared media supplies the resolution and codec the info bar shows`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onPrepared(media(width = 1_920, height = 1_080, video = "video/avc", audio = "audio/mp4a-latm"))

        assertThat(machine.state.value.infoBar?.qualityLabel).isEqualTo("1080p avc mp4a-latm")
    }

    @Test
    fun `an undeclared size keeps the stream row label`() {
        machine.onWatchStarted(1, 10, infoBar(quality = "SD"))
        machine.onPrepared(media(width = 0, height = 0, video = null, audio = null))

        assertThat(machine.state.value.infoBar?.qualityLabel).isEqualTo("SD")
    }

    @Test
    fun `first frame leaves the buffering phase even when a state callback was coalesced`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onEngineState(EngineState.PREPARING)

        assertThat(machine.onFirstFrame(1_234L).phase).isEqualTo(PlaybackPhase.PLAYING)
    }

    @Test
    fun `audio tracks and aspect ratio are part of the same state`() {
        machine.onAudioTracks(listOf(AudioTrackInfo("audio:0:0", "Track 1", "zho", "audio/ac3", true)), "audio:0:0")

        assertThat(machine.state.value.selectedAudioTrackId).isEqualTo("audio:0:0")
        assertThat(machine.onAspectRatio(AspectRatioMode.ZOOM).aspectRatio).isEqualTo(AspectRatioMode.ZOOM)
    }

    @Test
    fun `stopped and released are distinct terminal states`() {
        assertThat(machine.onStopped().phase).isEqualTo(PlaybackPhase.STOPPED)
        assertThat(machine.onReleased().phase).isEqualTo(PlaybackPhase.RELEASED)
    }

    // ---------------------------------------------------------------- P1-5 fail-over wiring

    @Test
    fun `a failover decision overrides the engine mapping and shows the hint`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onError(error(FailureClass.HTTP_CLIENT, retryable = false, status = 404))

        val state = machine.onFailoverRunning("正在切换备用源…")

        assertThat(state.phase).isEqualTo(PlaybackPhase.FAILOVER)
        assertThat(state.infoBar?.failoverHint).isEqualTo("正在切换备用源…")
        // The per-error text is cleared: the screen now says what the state machine is doing.
        assertThat(state.errorText).isNull()
        assertThat(state.lastError).isNull()
    }

    @Test
    fun `a completed switch counts and names the stream that is now playing`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onFailoverRunning("正在切换备用源…")

        val state = machine.onFailoverSwitched(toStreamId = 11, hint = "已切换备用源")

        assertThat(state.activeStreamId).isEqualTo(11)
        assertThat(state.failoverCount).isEqualTo(1)
        assertThat(state.infoBar?.failoverHint).isEqualTo("已切换备用源")
    }

    @Test
    fun `giving up shows the policy message as the failure text`() {
        machine.onWatchStarted(1, 10, infoBar())
        val error = error(FailureClass.PLAYLIST_GONE, retryable = false)

        val state = machine.onFailoverExhausted(error, "该频道暂时不可用")

        assertThat(state.phase).isEqualTo(PlaybackPhase.ERROR)
        assertThat(state.errorText).isEqualTo("该频道暂时不可用")
        assertThat(state.lastError).isEqualTo(error)
        assertThat(state.infoBar?.failoverHint).isEqualTo("该频道暂时不可用")
    }

    @Test
    fun `a failover re-prepare keeps the status line on screen`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onFailoverRunning("正在切换备用源…")

        // The controller re-prepares the same channel with the backup stream: same channel, FAILOVER.
        val state = machine.onWatchStarted(1, 11, infoBar())

        assertThat(state.phase).isEqualTo(PlaybackPhase.PREPARING)
        assertThat(state.activeStreamId).isEqualTo(11)
        assertThat(state.infoBar?.failoverHint).isEqualTo("正在切换备用源…")
    }

    @Test
    fun `a retry after giving up starts with a clean status line`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onFailoverExhausted(error(FailureClass.PLAYLIST_GONE, retryable = false), "该频道暂时不可用")

        val state = machine.onWatchStarted(1, 10, infoBar())

        assertThat(state.infoBar?.failoverHint).isNull()
    }

    @Test
    fun `switching to another channel starts with a clean status line`() {
        machine.onWatchStarted(1, 10, infoBar())
        machine.onFailoverRunning("正在切换备用源…")

        val state = machine.onWatchStarted(2, 20, infoBar())

        assertThat(state.infoBar?.failoverHint).isNull()
    }

    private fun infoBar(quality: String? = null) = InfoBarState(
        channelName = "CCTV1",
        logoUrl = null,
        qualityLabel = quality,
        nowNext = null,
    )

    // ---------------------------------------------------------------- EPG now/next (P2-7)

    @Test
    fun `the epg line lands on the open info bar without disturbing the rest of it`() {
        val machine = PlaybackUiStateMachine()
        machine.onWatchStarted(1, 10, infoBar(quality = "1080p"))
        machine.onFailoverRunning("正在切换备用源…")

        val nowNext = NowNext(
            now = programme("新闻联播"),
            next = programme("焦点访谈"),
        )
        val state = machine.onNowNext(nowNext)

        assertThat(state.infoBar?.nowNext?.now?.title).isEqualTo("新闻联播")
        // The rest of the bar is untouched: the EPG arrives late and must not clear the fail-over line.
        assertThat(state.infoBar?.qualityLabel).isEqualTo("1080p")
        assertThat(state.infoBar?.failoverHint).isEqualTo("正在切换备用源…")
    }

    @Test
    fun `a channel without epg clears the line and leaves the bar standing`() {
        val machine = PlaybackUiStateMachine()
        machine.onWatchStarted(1, 10, infoBar(quality = "720p"))
        machine.onNowNext(NowNext(now = programme("X"), next = null))

        val state = machine.onNowNext(null)

        assertThat(state.infoBar?.nowNext).isNull()
        assertThat(state.infoBar?.channelName).isEqualTo("CCTV1")
        assertThat(state.infoBar?.qualityLabel).isEqualTo("720p")
    }

    @Test
    fun `an epg answer that arrives before the bar exists is dropped, not invented`() {
        val machine = PlaybackUiStateMachine()
        val state = machine.onNowNext(NowNext(now = programme("X"), next = null))
        assertThat(state.infoBar).isNull()
    }

    private fun programme(title: String) = Programme(
        id = 0,
        epgChannelId = "c1",
        startMs = 0,
        stopMs = 1,
        title = title,
        desc = null,
        category = null,
    )

    private fun error(failure: FailureClass, retryable: Boolean, status: Int? = null) = AppError(
        code = EventCodes.PLAY_PREPARE_FAIL,
        failure = failure,
        retryable = retryable,
        httpStatus = status,
    )

    private fun media(width: Int, height: Int, video: String?, audio: String?) = PreparedMedia(
        streamId = 10,
        durationMs = null,
        isLive = true,
        videoCodec = video,
        audioCodec = audio,
        width = width,
        height = height,
        audioTracks = emptyList(),
    )
}
