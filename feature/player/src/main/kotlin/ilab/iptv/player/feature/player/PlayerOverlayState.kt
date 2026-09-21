package ilab.iptv.player.feature.player

import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState

/**
 * What sits on top of the video while it is not showing a picture (P1-4 item 3: "状态可视化：缓冲中/
 * 起播中/失败提示").
 *
 * Pure: the phase → overlay decision is the part of the screen a test can pin down, and the screen
 * only renders the result.
 */
data class PlayerOverlay(
    val kind: Kind,
    val message: String?,
    /** The failure overlay is the retry entry point (P1-4 item 3), so it is the focusable one. */
    val retryable: Boolean,
) {
    enum class Kind { NONE, STARTING, BUFFERING, ERROR }

    companion object {
        val NONE = PlayerOverlay(Kind.NONE, null, retryable = false)
        val STARTING = PlayerOverlay(Kind.STARTING, "正在起播…", retryable = false)
        val BUFFERING = PlayerOverlay(Kind.BUFFERING, "缓冲中…", retryable = false)

        fun error(message: String): PlayerOverlay = PlayerOverlay(Kind.ERROR, message, retryable = true)
    }
}

object PlayerOverlayState {

    /**
     * [fault] is the screen-level failure the repository produced (unknown channel, no stream) and
     * the session's own failure ([PlaybackUiState.errorText]) is the playback-level one. Both end in
     * the same overlay, because to the user they are the same thing: no picture, here is why, press
     * retry.
     */
    fun of(state: PlaybackUiState, fault: String?): PlayerOverlay = when {
        fault != null -> PlayerOverlay.error(fault)
        state.phase == PlaybackPhase.ERROR ->
            PlayerOverlay.error(state.errorText ?: "播放失败，按「重试」再试一次")

        state.phase == PlaybackPhase.PREPARING || state.phase == PlaybackPhase.IDLE -> PlayerOverlay.STARTING
        state.phase == PlaybackPhase.BUFFERING -> PlayerOverlay.BUFFERING
        state.phase == PlaybackPhase.FAILOVER -> PlayerOverlay.BUFFERING
        // PLAYING / STOPPED / RELEASED show the video itself; the info bar carries the state.
        else -> PlayerOverlay.NONE
    }
}
