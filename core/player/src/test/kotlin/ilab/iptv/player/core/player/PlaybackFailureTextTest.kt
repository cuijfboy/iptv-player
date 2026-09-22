package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.FailureOrigin
import org.junit.Test

/**
 * Every failure class of docs/02 §4.6 must reach the user as words plus a next step — that is the
 * whole point of P1-4 item 3's "可读提示与重试入口". This test walks the taxonomy so a new class
 * cannot be added without deciding what the TV says about it.
 */
class PlaybackFailureTextTest {

    @Test
    fun `every failure class produces a non-empty sentence`() {
        FailureClass.entries.forEach { failure ->
            val text = PlaybackFailureText.of(error(failure))
            assertThat(text).isNotEmpty()
            assertThat(PlaybackFailureText.shortLabel(error(failure))).isNotEmpty()
        }
    }

    @Test
    fun `a retryable failure tells the user to retry`() {
        val text = PlaybackFailureText.of(error(FailureClass.TIMEOUT, retryable = true))

        assertThat(text).contains("超时")
        assertThat(text).contains("重试")
    }

    @Test
    fun `a dead stream points at switching instead of retrying`() {
        val text = PlaybackFailureText.of(
            error(FailureClass.HTTP_CLIENT, retryable = false, status = 404),
        )

        assertThat(text).contains("HTTP 404")
        assertThat(text).doesNotContain("重试")
        assertThat(text).contains("频道")
    }

    @Test
    fun `cancellation is not dressed up as a failure`() {
        val text = PlaybackFailureText.of(error(FailureClass.CANCELLED, retryable = false))

        assertThat(text).isEqualTo("播放已取消")
    }

    @Test
    fun `a missing status still names the cause instead of printing null`() {
        val text = PlaybackFailureText.of(error(FailureClass.HTTP_SERVER, retryable = true, status = null))

        assertThat(text).contains("HTTP 5xx")
        assertThat(text).doesNotContain("null")
    }

    @Test
    fun `an environment gate is named as the gate, not as an unavailable source`() {
        // 418 is a gate refusal (docs/05 66): the TV must not claim the channel's own source is dead.
        val text = PlaybackFailureText.of(
            error(FailureClass.HTTP_CLIENT, retryable = true, status = 418, origin = FailureOrigin.ENV_GATED),
        )
        assertThat(text).contains("未放行")
        assertThat(text).contains("HTTP 418")
        assertThat(text).contains("重试")
        assertThat(text).doesNotContain("该频道源不可用")
        assertThat(PlaybackFailureText.shortLabel(error(FailureClass.HTTP_CLIENT, status = 418, origin = FailureOrigin.ENV_GATED)))
            .isEqualTo("网关未放行")
    }

    private fun error(
        failure: FailureClass,
        retryable: Boolean = false,
        status: Int? = null,
        origin: FailureOrigin = FailureOrigin.SOURCE,
    ) = AppError(
        code = EventCodes.PLAY_PREPARE_FAIL,
        failure = failure,
        retryable = retryable,
        httpStatus = status,
        origin = origin,
    )
}
