package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState
import org.junit.Test

/** P1-4 item 3: 缓冲中 / 起播中 / 失败提示 — and nothing on screen while the picture is fine. */
class PlayerOverlayStateTest {

    @Test
    fun `preparing shows starting and buffering shows buffering`() {
        assertThat(overlay(PlaybackPhase.PREPARING, null, null).kind)
            .isEqualTo(PlayerOverlay.Kind.STARTING)
        assertThat(overlay(PlaybackPhase.BUFFERING, null, null).kind)
            .isEqualTo(PlayerOverlay.Kind.BUFFERING)
    }

    @Test
    fun `playing shows no overlay at all`() {
        assertThat(overlay(PlaybackPhase.PLAYING, null, null)).isEqualTo(PlayerOverlay.NONE)
    }

    @Test
    fun `a session failure shows the readable text and offers retry`() {
        val result = overlay(PlaybackPhase.ERROR, "网络不可达，按「重试」再试一次", null)

        assertThat(result.kind).isEqualTo(PlayerOverlay.Kind.ERROR)
        assertThat(result.message).contains("重试")
        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `a screen-level fault wins over the session phase`() {
        val result = overlay(PlaybackPhase.PLAYING, null, "找不到频道（编号 42）")

        assertThat(result.kind).isEqualTo(PlayerOverlay.Kind.ERROR)
        assertThat(result.message).contains("42")
    }

    @Test
    fun `an error without text still says something actionable`() {
        val result = overlay(PlaybackPhase.ERROR, null, null)

        assertThat(result.message).isNotEmpty()
        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `failover is presented as buffering until it lands`() {
        assertThat(overlay(PlaybackPhase.FAILOVER, null, null).kind)
            .isEqualTo(PlayerOverlay.Kind.BUFFERING)
    }

    private fun overlay(phase: PlaybackPhase, errorText: String?, fault: String?): PlayerOverlay =
        PlayerOverlayState.of(
            PlaybackUiState.EMPTY.copy(
                phase = phase,
                aspectRatio = AspectRatioMode.FIT,
                errorText = errorText,
            ),
            fault,
        )
}
