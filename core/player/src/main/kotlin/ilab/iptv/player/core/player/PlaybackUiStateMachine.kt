package ilab.iptv.player.core.player

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.AudioTrackInfo
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.InfoBarState
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.model.PreparedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single writer of [PlaybackUiState] (docs/02 §4.5 C1): engine state and engine events go in,
 * the UI state the player screen renders comes out.
 *
 * Pure on purpose (no Android, no media3): the whole phase/failure/aperture transition table is
 * unit-testable, which is what P1-4 asks for ("可纯化部分：信息条超时逻辑、返回键状态机、宽高比映射"
 * plus this reducer). [PlaybackSession] is the Android half that feeds it.
 *
 * Phase mapping is the frozen table of §4.5 C1 and nothing else: IDLE→IDLE, PREPARING→PREPARING,
 * READY/BUFFERING→BUFFERING, PLAYING→PLAYING, ENDED/RELEASED→STOPPED, ERROR→ERROR.
 */
class PlaybackUiStateMachine(initial: PlaybackUiState = PlaybackUiState.EMPTY) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * A new `watch` command: back to PREPARING with the info bar that belongs to the new channel.
     *
     * The ONE thing carried over is the fail-over status line, and only while a fail-over is in
     * flight (`FAILOVER` → same channel): a switch re-prepares the channel, and the user must keep
     * seeing "正在切换备用源…" across that re-prepare instead of the line blinking off (found on the
     * device, P1-5 §4). A different channel, or a retry from the failure overlay, starts clean.
     */
    fun onWatchStarted(channelId: Long, streamId: Long, infoBar: InfoBarState): PlaybackUiState {
        val previous = _state.value
        val inFlightFailover = previous.phase == PlaybackPhase.FAILOVER && previous.channelId == channelId
        val hint = previous.infoBar?.failoverHint?.takeIf { inFlightFailover }
        return publish(
            previous.copy(
                channelId = channelId,
                activeStreamId = streamId,
                phase = PlaybackPhase.PREPARING,
                infoBar = infoBar.copy(failoverHint = hint),
                lastError = null,
                errorText = null,
            ),
        )
    }

    /**
     * Engine phase → business phase (§4.5 C1). `FAILOVER` is deliberately absent: no failover
     * decision exists yet (P1-6 owns it), and a phase nobody can produce is a lie in the state.
     */
    fun onEngineState(engineState: EngineState): PlaybackUiState = publish(
        _state.value.copy(phase = phaseOf(engineState)),
    )

    /**
     * The stream is ready. This carries the codec/resolution the info bar shows ("清晰度或编码", P1-4
     * item 2) — the engine reports it in [PreparedMedia], so the bar never guesses.
     */
    fun onPrepared(media: PreparedMedia): PlaybackUiState = publish(
        _state.value.copy(
            activeStreamId = media.streamId,
            audioTracks = media.audioTracks,
            infoBar = _state.value.infoBar?.copy(qualityLabel = qualityLabelOf(media, _state.value.infoBar?.qualityLabel)),
        ),
    )

    /**
     * First frame rendered (§7.3). The engine state usually says PLAYING by then, but the audio-only
     * path emits this at the same "now it is really playing" moment, so it is the honest signal for
     * the UI to leave the buffering overlay even if a state callback was coalesced away.
     */
    fun onFirstFrame(costMs: Long): PlaybackUiState = publish(
        _state.value.copy(phase = PlaybackPhase.PLAYING),
    )

    /** A failure that must stop the "starting" spinner and show a retry entry point (P1-4 item 3). */
    fun onError(error: AppError): PlaybackUiState = publish(
        _state.value.copy(
            phase = PlaybackPhase.ERROR,
            lastError = error,
            errorText = PlaybackFailureText.of(error),
        ),
    )

    /** The user pressed retry: clear the failure, go back to the starting phase (attempt is P1-6's). */
    fun onRetryRequested(): PlaybackUiState = publish(
        _state.value.copy(phase = PlaybackPhase.PREPARING, lastError = null, errorText = null),
    )

    // ---------------------------------------------------------------- fail-over (P1-5 wiring)

    /**
     * A fail-over decision is being applied (docs/02 §4.5 C1: "发生切换决策时置 `FAILOVER`（覆盖映射）").
     *
     * This is the one phase the engine cannot produce: the engine only reports `EngineState` and the
     * controller overwrites the mapping while it decides and re-prepares. The hint is what the info
     * bar shows ("正在切换备用源…" / "正在重试…"), so the user can see the state machine working.
     */
    fun onFailoverRunning(hint: String): PlaybackUiState = publish(
        _state.value.copy(
            phase = PlaybackPhase.FAILOVER,
            infoBar = _state.value.infoBar?.copy(failoverHint = hint),
            lastError = null,
            errorText = null,
        ),
    )

    /**
     * The switch is done: the channel is now playing [toStreamId]. `failoverCount` is the session's
     * "how many times did we have to move" number that `PLAY_END` and the info bar report.
     */
    fun onFailoverSwitched(toStreamId: Long, hint: String): PlaybackUiState = publish(
        _state.value.copy(
            activeStreamId = toStreamId,
            phase = PlaybackPhase.BUFFERING,
            failoverCount = _state.value.failoverCount + 1,
            infoBar = _state.value.infoBar?.copy(failoverHint = hint),
            lastError = null,
            errorText = null,
        ),
    )

    /**
     * Every candidate is gone (docs/01 F4): no source left, so the screen shows the row's user
     * message ("该频道暂时不可用") as the failure text instead of the per-error hint.
     */
    fun onFailoverExhausted(error: AppError?, message: String): PlaybackUiState = publish(
        _state.value.copy(
            phase = PlaybackPhase.ERROR,
            infoBar = _state.value.infoBar?.copy(failoverHint = message),
            lastError = error,
            errorText = message,
        ),
    )

    fun onAudioTracks(tracks: List<AudioTrackInfo>, selectedId: String?): PlaybackUiState = publish(
        _state.value.copy(audioTracks = tracks, selectedAudioTrackId = selectedId),
    )

    fun onAspectRatio(mode: AspectRatioMode): PlaybackUiState = publish(
        _state.value.copy(aspectRatio = mode),
    )

    /** User pressed stop / left the player page: the session is over, the engine stays alive (P3). */
    fun onStopped(): PlaybackUiState = publish(_state.value.copy(phase = PlaybackPhase.STOPPED))

    /** The engine itself was released: terminal state (docs/02 §7.2 P5). */
    fun onReleased(): PlaybackUiState = publish(_state.value.copy(phase = PlaybackPhase.RELEASED))

    /** Info bar text refresh (channel metadata arrives from the repository after the intent). */
    fun onInfoBar(infoBar: InfoBarState?): PlaybackUiState = publish(_state.value.copy(infoBar = infoBar))

    private fun publish(next: PlaybackUiState): PlaybackUiState {
        _state.value = next
        return next
    }

    private fun phaseOf(engineState: EngineState): PlaybackPhase = when (engineState) {
        EngineState.IDLE -> PlaybackPhase.IDLE
        EngineState.PREPARING -> PlaybackPhase.PREPARING
        EngineState.READY, EngineState.BUFFERING -> PlaybackPhase.BUFFERING
        EngineState.PLAYING -> PlaybackPhase.PLAYING
        EngineState.ENDED, EngineState.RELEASED -> PlaybackPhase.STOPPED
        EngineState.ERROR -> PlaybackPhase.ERROR
    }

    /**
     * "1080p H.264 / AAC" — resolution first (what the user asks about), codec as the fallback when
     * the stream did not declare a size. A stream with neither keeps whatever the stream row said.
     */
    private fun qualityLabelOf(media: PreparedMedia, fallback: String?): String? {
        val resolution = when {
            media.height >= 2_160 -> "2160p"
            media.height >= 1_080 -> "1080p"
            media.height >= 720 -> "720p"
            media.height >= 480 -> "480p"
            media.height > 0 -> "${media.height}p"
            else -> null
        }
        val codec = media.videoCodec?.substringAfterLast('/')
        val parts = listOfNotNull(resolution, codec, media.audioCodec?.substringAfterLast('/'))
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: fallback
    }
}
