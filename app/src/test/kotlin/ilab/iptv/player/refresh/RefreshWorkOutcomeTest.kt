package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import androidx.work.ListenableWorker
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.RefreshInterruption
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshTrigger
import org.junit.Test

/**
 * The retry policy of docs/04 P2-5 item 1, as a table: what the worker answers WorkManager after a
 * run. The interesting rows are `interrupted` (success, because the budget cut is by design) and
 * `gaveUp` (success, because a failed *periodic* worker can take the whole daily schedule with it).
 */
class RefreshWorkOutcomeTest {

    private val maxAttempts = RefreshWorkSpec.MAX_ATTEMPTS

    private fun resultOf(result: RefreshRunResult, attempt: Int): ListenableWorker.Result =
        RefreshWorkOutcome.toResult(result, runAttempt = attempt, maxAttempts = maxAttempts)

    @Test
    fun `a completed run succeeds and reports its phase`() {
        val result = resultOf(
            RefreshRunResult.Completed(frame(RefreshPhase.DONE, ok = 5, total = 5), interrupted = null),
            attempt = 0,
        )

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertThat(data.getString(RefreshWorkOutcome.KEY_RESULT))
            .isEqualTo(RefreshWorkOutcome.RESULT_COMPLETED)
        assertThat(data.getString(RefreshWorkOutcome.KEY_PHASE)).isEqualTo("DONE")
    }

    @Test
    fun `a budget-interrupted run is a success, because retrying would burn the same budget again`() {
        val result = resultOf(
            RefreshRunResult.Completed(
                frame(
                    RefreshPhase.DONE,
                    interrupted = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.DEEP),
                ),
                interrupted = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.DEEP),
            ),
            attempt = 0,
        )

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat((result as ListenableWorker.Result.Success).outputData.getString(RefreshWorkOutcome.KEY_INTERRUPTED))
            .isEqualTo(InterruptionReason.BUDGET_EXCEEDED.name)
    }

    @Test
    fun `a deferral retries and stops retrying when the attempts run out`() {
        val deferred = RefreshRunResult.Deferred(RefreshTrigger.SCHEDULED, deferrals = 0)

        assertThat(resultOf(deferred, attempt = 0)).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        assertThat(resultOf(deferred, attempt = 1)).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        assertThat(resultOf(deferred, attempt = maxAttempts - 1))
            .isInstanceOf(ListenableWorker.Result.Success::class.java)
    }

    @Test
    fun `a failure retries up to the cap and then gives up without killing the schedule`() {
        val failed = RefreshRunResult.Failed(IllegalStateException("boom"))

        assertThat(resultOf(failed, attempt = 0)).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        assertThat(resultOf(failed, attempt = 1)).isInstanceOf(ListenableWorker.Result.Retry::class.java)

        val last = resultOf(failed, attempt = maxAttempts - 1)
        assertThat(last).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val data = (last as ListenableWorker.Result.Success).outputData
        assertThat(data.getString(RefreshWorkOutcome.KEY_RESULT)).isEqualTo(RefreshWorkOutcome.RESULT_GAVE_UP)
        assertThat(data.getBoolean(RefreshWorkOutcome.KEY_GAVE_UP, false)).isTrue()
    }

    @Test
    fun `the attempt cap is three, so a run gives up after at most 90 min of backoff`() {
        assertThat(maxAttempts).isEqualTo(3)
        assertThat(RefreshWorkSpec.BACKOFF_MS * maxAttempts).isEqualTo(90L * 60_000L)
    }

    /**
     * P2-9: the 更新 step reads the payload to tell "the update worked" from "the job succeeded", so
     * the two counters have to actually be in it. The retry policy above is unchanged — these keys are
     * additive, which is why this test asserts the values rather than the shape.
     */
    @Test
    fun `a completed run reports the counters the wizard shows`() {
        val result = resultOf(
            RefreshRunResult.Completed(
                frame(RefreshPhase.DONE, done = 415, total = 415, ok = 412, fail = 3),
                interrupted = null,
            ),
            attempt = 0,
        )

        val data = (result as ListenableWorker.Result.Success).outputData
        assertThat(data.getInt(RefreshWorkOutcome.KEY_OK_COUNT, -1)).isEqualTo(412)
        assertThat(data.getInt(RefreshWorkOutcome.KEY_FAIL_COUNT, -1)).isEqualTo(3)
    }

    /** P2-9: "失败给可读提示" needs the failure's class name in the payload, not only in the log. */
    @Test
    fun `giving up names the failure`() {
        val last = resultOf(
            RefreshRunResult.Failed(IllegalStateException("boom")),
            attempt = maxAttempts - 1,
        )

        val data = (last as ListenableWorker.Result.Success).outputData
        assertThat(data.getString(RefreshWorkOutcome.KEY_ERROR)).isEqualTo("IllegalStateException")
    }
}
