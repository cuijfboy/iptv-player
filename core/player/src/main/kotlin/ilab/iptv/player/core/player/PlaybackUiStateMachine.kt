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

    /** A new `watch` command: back to PREPARING with the info bar that belongs to the new channel. */
    fun onWatchStarted(channelId: Long, streamId: Long, infoBar: InfoBarState): PlaybackUiState =
        publish(
            _state.value.copy(
                channelId = channelId,
                activeStreamId = streamId,
                phase = PlaybackPhase.PREPARING,
                infoBar = infoBar,
                lastError = null,
                errorText = null,
            ),
        )

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
