package ilab.iptv.player.epg

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.RefreshTrigger
import org.junit.Test

/**
 * What the EPG worker answers WorkManager (P3-6), as a table — same shape as P2-5's
 * `RefreshWorkOutcomeTest`, and the same reason: the retry policy is the part that silently misbehaves
 * on a device, so it is asserted without one.
 */
class EpgRefreshWorkOutcomeTest {

    private val maxAttempts = EpgRefreshWorkSpec.MAX_ATTEMPTS

    private fun resultOf(result: EpgRunResult, attempt: Int): ListenableWorker.Result =
        EpgRefreshWorkOutcome.toResult(result, runAttempt = attempt, maxAttempts = maxAttempts)

    @Test
    fun `a completed run succeeds and carries the coverage numbers`() {
        val result = resultOf(EpgRunResult.Completed(FakeEpgRunner.report()), attempt = 0)

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertThat(data.getString(EpgRefreshWorkOutcome.KEY_RESULT))
            .isEqualTo(EpgRefreshWorkOutcome.RESULT_COMPLETED)
        assertThat(data.getInt(EpgRefreshWorkOutcome.KEY_PROGRAMMES, 0)).isEqualTo(177_447)
        assertThat(data.getString(EpgRefreshWorkOutcome.KEY_INTERRUPTED)).isNull()
    }

    @Test
    fun `a run stopped by playback still succeeds, but says it was interrupted`() {
        val result = resultOf(
            EpgRunResult.Completed(FakeEpgRunner.report(interrupted = "playback_priority")),
            attempt = 0,
        )

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(
            (result as ListenableWorker.Result.Success).outputData
                .getString(EpgRefreshWorkOutcome.KEY_INTERRUPTED),
        ).isEqualTo("playback_priority")
    }

    @Test
    fun `a skipped run succeeds, because retrying would defeat the freshness gate`() {
        val result = resultOf(EpgRunResult.Skipped("fresh"), attempt = 0)

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertThat(data.getString(EpgRefreshWorkOutcome.KEY_RESULT))
            .isEqualTo(EpgRefreshWorkOutcome.RESULT_SKIPPED)
        assertThat(data.getString(EpgRefreshWorkOutcome.KEY_REASON)).isEqualTo("fresh")
    }

    @Test
    fun `a deferral retries and stops retrying when the attempts run out`() {
        val deferred = EpgRunResult.Deferred(RefreshTrigger.SCHEDULED, deferrals = 0)

        assertThat(resultOf(deferred, attempt = 0)).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        assertThat(resultOf(deferred, attempt = 1)).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        assertThat(resultOf(deferred, attempt = maxAttempts - 1))
            .isInstanceOf(ListenableWorker.Result.Success::class.java)
    }

    @Test
    fun `a failure retries up to the cap and then gives up`() {
        val failed = EpgRunResult.Failed("budget_exceeded")

        assertThat(resultOf(failed, attempt = 0)).isInstanceOf(ListenableWorker.Result.Retry::class.java)

        val last = resultOf(failed, attempt = maxAttempts - 1)
        assertThat(last).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val data = (last as ListenableWorker.Result.Success).outputData
        assertThat(data.getString(EpgRefreshWorkOutcome.KEY_RESULT))
            .isEqualTo(EpgRefreshWorkOutcome.RESULT_GAVE_UP)
        assertThat(data.getBoolean(EpgRefreshWorkOutcome.KEY_GAVE_UP, false)).isTrue()
    }

    @Test
    fun `the retry story matches the refresh job, three attempts thirty minutes apart`() {
        assertThat(maxAttempts).isEqualTo(3)
        assertThat(EpgRefreshWorkSpec.immediate(RefreshTrigger.SCHEDULED).backoffMs)
            .isEqualTo(30L * 60_000L)
    }
}
