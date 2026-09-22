package ilab.iptv.player.core.domain.wizard

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.RefreshPhase
import org.junit.Test

/**
 * docs/04 P2-9 item 2 ②: "更新（前台服务 + 进度 + 可中断；失败给可读提示与重试）" — the reading half.
 *
 * The interesting cases are the ones that would otherwise be reported as success: P2-5's worker
 * answers WorkManager with `success` for `gaveUp` and `deferred` on purpose, so "the job succeeded"
 * and "the update worked" are different questions and the wizard has to ask the second one.
 */
class WizardUpdateReadingTest {

    private fun snapshot(
        run: WizardUpdateRun = WizardUpdateRun.NONE,
        phase: RefreshPhase? = null,
        done: Int = 0,
        total: Int = 0,
        result: WizardUpdateResult? = null,
        ok: Int = 0,
        fail: Int = 0,
        interrupted: Boolean = false,
        detail: String? = null,
        wait: WizardUpdateWait = WizardUpdateWait.AWAITING_CONSTRAINTS,
    ) = WizardUpdateSnapshot(
        run = run,
        phase = phase,
        done = done,
        total = total,
        result = result,
        okCount = ok,
        failCount = fail,
        interrupted = interrupted,
        detail = detail,
        wait = wait,
    )

    @Test
    fun `nothing has been asked for yet`() {
        assertThat(WizardUpdateReading.of(snapshot())).isEqualTo(WizardUpdateState.NotStarted)
    }

    @Test
    fun `queued and running are distinct states, because only one of them can be interrupted`() {
        assertThat(WizardUpdateReading.of(snapshot(run = WizardUpdateRun.QUEUED)))
            .isEqualTo(WizardUpdateState.Preparing(WizardUpdateWait.AWAITING_CONSTRAINTS))
        assertThat(WizardUpdateReading.of(snapshot(run = WizardUpdateRun.RUNNING)))
            .isEqualTo(WizardUpdateState.Running(phase = null, percent = null))
    }

    @Test
    fun `a queued run that follows an interruption says so instead of blaming the network`() {
        // NEW-004: the QA round saw 「已排队，等待网络…」 for a run that was really waiting out the
        // backoff of the attempt the process death killed. The two waits are different readings of the
        // same WorkManager state, so the difference has to survive this mapping.
        val read = WizardUpdateReading.of(
            snapshot(run = WizardUpdateRun.QUEUED, wait = WizardUpdateWait.RETRY_AFTER_INTERRUPTION),
        )

        assertThat(read).isEqualTo(
            WizardUpdateState.Preparing(WizardUpdateWait.RETRY_AFTER_INTERRUPTION),
        )
    }

    @Test
    fun `a running phase with a denominator reports a percentage`() {
        val read = WizardUpdateReading.of(
            snapshot(run = WizardUpdateRun.RUNNING, phase = RefreshPhase.DEEP, done = 25, total = 50),
        )

        assertThat(read).isEqualTo(WizardUpdateState.Running(phase = RefreshPhase.DEEP, percent = 50))
    }

    @Test
    fun `a phase without a denominator stays indeterminate instead of showing zero`() {
        // The same rule as the P2-5 notification: a bar that jumps to 0 % (or to 100 %) because the
        // phase has no item counter yet is worse than an indeterminate one.
        assertThat(WizardUpdateReading.percentOf(snapshot(phase = RefreshPhase.FETCH, done = 0, total = 0)))
            .isNull()
        // DONE is 100 % even with empty counters.
        assertThat(WizardUpdateReading.percentOf(snapshot(phase = RefreshPhase.DONE, done = 0, total = 0)))
            .isEqualTo(100)
    }

    @Test
    fun `a percentage can never leave zero to one hundred`() {
        assertThat(WizardUpdateReading.percentOf(snapshot(phase = RefreshPhase.PERSIST, done = 7, total = 5)))
            .isEqualTo(100)
        assertThat(WizardUpdateReading.percentOf(snapshot(phase = RefreshPhase.PERSIST, done = -3, total = 5)))
            .isEqualTo(0)
    }

    @Test
    fun `a completed run reports what it wrote`() {
        val read = WizardUpdateReading.of(
            snapshot(
                run = WizardUpdateRun.SUCCEEDED,
                phase = RefreshPhase.DONE,
                result = WizardUpdateResult.COMPLETED,
                ok = 412,
                fail = 3,
            ),
        )

        assertThat(read).isEqualTo(WizardUpdateState.Done(okCount = 412, failCount = 3, partial = false))
    }

    @Test
    fun `a budget-interrupted run is done and says so`() {
        val read = WizardUpdateReading.of(
            snapshot(
                run = WizardUpdateRun.SUCCEEDED,
                result = WizardUpdateResult.COMPLETED,
                ok = 12,
                interrupted = true,
            ),
        )

        assertThat(read).isEqualTo(WizardUpdateState.Done(okCount = 12, failCount = 0, partial = true))
    }

    @Test
    fun `gave up is a failure even though the job reports success`() {
        val read = WizardUpdateReading.of(
            snapshot(
                run = WizardUpdateRun.SUCCEEDED,
                result = WizardUpdateResult.GAVE_UP,
                detail = "IOException",
            ),
        )

        assertThat(read).isEqualTo(
            WizardUpdateState.Failed(WizardUpdateFailure.GAVE_UP, "IOException"),
        )
    }

    @Test
    fun `a deferral is reported as a deferral, not as a silent success`() {
        val read = WizardUpdateReading.of(
            snapshot(run = WizardUpdateRun.SUCCEEDED, result = WizardUpdateResult.DEFERRED),
        )

        assertThat(read).isEqualTo(WizardUpdateState.Failed(WizardUpdateFailure.DEFERRED, null))
    }

    @Test
    fun `a missing or unknown payload is a failure, never a promise of channels`() {
        assertThat(WizardUpdateReading.of(snapshot(run = WizardUpdateRun.SUCCEEDED, result = null)))
            .isEqualTo(WizardUpdateState.Failed(WizardUpdateFailure.UNKNOWN, null))
        assertThat(
            WizardUpdateReading.of(
                snapshot(run = WizardUpdateRun.SUCCEEDED, result = WizardUpdateResult.UNKNOWN),
            ),
        ).isEqualTo(WizardUpdateState.Failed(WizardUpdateFailure.UNKNOWN, null))
    }

    @Test
    fun `a failed job and a cancellation keep their own state`() {
        assertThat(WizardUpdateReading.of(snapshot(run = WizardUpdateRun.FAILED, detail = "TimeoutException")))
            .isEqualTo(WizardUpdateState.Failed(WizardUpdateFailure.RUN_FAILED, "TimeoutException"))
        assertThat(WizardUpdateReading.of(snapshot(run = WizardUpdateRun.CANCELLED)))
            .isEqualTo(WizardUpdateState.Cancelled)
    }
}
