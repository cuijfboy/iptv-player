package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.RefreshInterruption
import ilab.iptv.player.core.model.RefreshPhase
import org.junit.Test

/**
 * The notification content decisions (docs/04 P2-5 item 2): the percentage arithmetic and the
 * dont-disturb content invariants. The Android painting is not tested here; what is not allowed to
 * alert, to divide by zero or to claim 107 percent is.
 */
class RefreshNotificationContentTest {

    @Test
    fun `a phase with a denominator shows its percentage and its stage label`() {
        val content = RefreshNotificationContent.of(
            frame(RefreshPhase.SHALLOW, done = 30, total = 120, ok = 25, fail = 5),
        )

        assertThat(content.percent).isEqualTo(25)
        assertThat(content.text).isEqualTo("轻量校验 · 25%")
        assertThat(content.title).isEqualTo(RefreshNotificationContent.RUNNING_TITLE)
        assertThat(content.ongoing).isTrue()
    }

    @Test
    fun `a phase without a denominator is indeterminate rather than 0 or 100 percent`() {
        val content = RefreshNotificationContent.of(frame(RefreshPhase.FETCH, done = 0, total = 0))

        assertThat(content.percent).isNull()
        assertThat(content.text).isEqualTo("获取源")
    }

    @Test
    fun `the percentage is clamped and the terminal frame is 100`() {
        assertThat(RefreshNotificationContent.percentOf(frame(RefreshPhase.DEEP, done = 9, total = 4)))
            .isEqualTo(100)
        assertThat(RefreshNotificationContent.percentOf(frame(RefreshPhase.DONE, done = 0, total = 0)))
            .isEqualTo(100)
        assertThat(RefreshNotificationContent.percentOf(frame(RefreshPhase.DEEP, done = 1, total = 3)))
            .isEqualTo(33)
    }

    @Test
    fun `every stage of the pipeline has a label`() {
        RefreshPhase.entries.forEach { phase ->
            assertThat(RefreshNotificationContent.phaseLabel(phase)).isNotEmpty()
        }
    }

    @Test
    fun `the terminal frames stop being ongoing, so the notification can be dismissed`() {
        assertThat(RefreshNotificationContent.done().ongoing).isFalse()
        assertThat(RefreshNotificationContent.deferred().ongoing).isFalse()
        assertThat(RefreshNotificationContent.failed().ongoing).isFalse()
        assertThat(RefreshNotificationContent.starting().ongoing).isTrue()
    }

    @Test
    fun `a budget-interrupted run still reports its last stage`() {
        val content = RefreshNotificationContent.of(
            frame(
                RefreshPhase.DEEP,
                done = 4,
                total = 40,
                interrupted = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.DEEP),
            ),
        )

        assertThat(content.text).isEqualTo("深度探测 · 10%")
    }
}
