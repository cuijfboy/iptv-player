package ilab.iptv.player.core.model

import ilab.iptv.player.core.common.AppError

/**
 * Playback transfer model (docs/02 §4.2, frozen interface v1) — the ONLY model that crosses the
 * engine ↔ application boundary. The UI never subscribes to an engine; `PlaybackController`
 * translates `EngineState`/`PlaybackEvent` into `PlaybackUiState` (docs/02 §4.5 C1).
 */

/** What an engine can do (docs/02 §4.2). `EngineSelector` matches these against a `DeviceProfile`. */
enum class EngineCapability { HLS, HTTP_TS, RTSP, DASH, AC3_PASSTHROUGH, AUDIO_TRACK_SELECT, ASPECT_RATIO, SUBTITLES }

/** The four display modes of docs/02 §7.4, exposed as an engine parameter (P1-3 item 3). */
enum class AspectRatioMode { FIT, FILL, ZOOM, FIXED_4_3 }

/** One `prepare` request (docs/02 §7.3). `timeoutMs` defaults to 12 s, per the frozen contract. */
data class PlaybackRequest(
    val channelId: Long,
    val stream: Stream,
    val startPositionMs: Long = 0,
    val timeoutMs: Long = 12_000,
    val preferPassthrough: Boolean = true,
    val sessionId: String,
)

/** What the engine hands back once the stream is ready (docs/02 §7.3). */
data class PreparedMedia(
    val streamId: Long,
    val durationMs: Long?,
    val isLive: Boolean,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val audioTracks: List<AudioTrackInfo>,
)

data class AudioTrackInfo(
    val id: String,
    val label: String,
    val language: String?,
    val codec: String,
    val isDefault: Boolean,
)

/** Engine-side state only. The UI phase mapping is docs/02 §4.5 C1, owned by the controller. */
enum class EngineState { IDLE, PREPARING, READY, BUFFERING, PLAYING, ENDED, ERROR, RELEASED }

enum class EndReason { USER_STOP, CHANNEL_SWITCH, COMPLETED, ERROR, APP_EXIT }

/**
 * Everything the engine reports upward (docs/02 §4.2). The engine never switches sources and never
 * writes `PLAY_*` logs itself: it emits these events and the controller decides (docs/02 §6.2).
 */
sealed interface PlaybackEvent {
    data class Prepared(val media: PreparedMedia, val costMs: Long) : PlaybackEvent

    /** docs/02 §7.3: the single source of `PLAY_FIRST_FRAME`. `costMs` = first frame − prepare start. */
    data class FirstFrame(val costMs: Long) : PlaybackEvent

    data class Stalled(val stalledMs: Long, val positionMs: Long) : PlaybackEvent
    data class Error(val error: AppError, val fatal: Boolean) : PlaybackEvent
    data class Ended(val reason: EndReason) : PlaybackEvent
    data class AudioTracks(val tracks: List<AudioTrackInfo>, val selectedId: String?) : PlaybackEvent
    data class Capabilities(val caps: Set<EngineCapability>) : PlaybackEvent
}

// ---------------------------------------------------------------- application-side state

/**
 * Business phase of one playback session (docs/02 §4.2). [PlaybackUiState] is the ONLY state the
 * UI consumes; the engine's [EngineState] never crosses into a View (docs/02 §4.5 C1).
 */
enum class PlaybackPhase { IDLE, PREPARING, BUFFERING, PLAYING, FAILOVER, STOPPED, ERROR, RELEASED }

/**
 * What the info bar shows (docs/02 §4.2, P1-4). `nowNext` is a **placeholder** this round: real EPG
 * data is P2-7, so the field is carried but stays null until then.
 */
data class InfoBarState(
    val channelName: String,
    val logoUrl: String?,
    val qualityLabel: String?,
    val nowNext: NowNext?,
    val failoverHint: String? = null,
)

/** Everything the player screen renders (docs/02 §4.2, frozen shape). */
data class PlaybackUiState(
    val channelId: Long?,
    val phase: PlaybackPhase,
    val activeStreamId: Long?,
    val infoBar: InfoBarState?,
    val aspectRatio: AspectRatioMode,
    val audioTracks: List<AudioTrackInfo>,
    val selectedAudioTrackId: String?,
    val failoverCount: Int,
    val lastError: AppError?,
    /** Human-readable failure for the on-screen prompt (P1-4 item 3: "失败要有可读提示与重试入口"). */
    val errorText: String?,
) {
    companion object {
        val EMPTY = PlaybackUiState(
            channelId = null,
            phase = PlaybackPhase.IDLE,
            activeStreamId = null,
            infoBar = null,
            aspectRatio = AspectRatioMode.FIT,
            audioTracks = emptyList(),
            selectedAudioTrackId = null,
            failoverCount = 0,
            lastError = null,
            errorText = null,
        )
    }
}
