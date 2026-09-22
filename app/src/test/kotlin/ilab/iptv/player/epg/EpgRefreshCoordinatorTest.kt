package ilab.iptv.player.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.refresh.FakeClock
import ilab.iptv.player.refresh.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The run half of P3-6, driven on the JVM: the stub runner counts calls, the stub status says how
 * fresh the stored guide is, and the probe says whether a session is playing.
 *
 * The two things this file has to prove beyond the policy table: a skipped/deferred run **does not
 * touch the pipeline** (that is the idempotency promise — no repeat write), and a budget overrun is
 * reported as a failure instead of hanging the job.
 */
class EpgRefreshCoordinatorTest {

    private val clock = FakeClock()
    private val logger = RecordingLogger()
    private val status = FakeEpgSourceStatus()
    private val guide = FakeEpgStoredGuide()
    private var playing = false

    private fun coordinator(
        runner: EpgRunner,
        settings: EpgRefreshSettings = EpgRefreshSettings(),
    ) = EpgRefreshCoordinator(
        runner = runner,
        policy = EpgRefreshPolicy(),
        settings = settings,
        status = status,
        guide = guide,
        playback = EpgRefreshCoordinator.PlaybackProbe { playing },
        clock = clock,
        logger = logger,
    )

    @Test
    fun `a run with no channel list waits instead of fetching into nothing`() = runTest {
        // BUG-20260922-016: this is the cold start that used to spend 26 s fetching guides for a table
        // that had no channels. Now it defers — and the job's retry is what picks the catalogue up.
        guide.set(channels = 0, matched = 0, programmed = 0)
        val runner = FakeEpgRunner()

        val result = coordinator(runner).run(RefreshTrigger.FIRST_RUN)

        assertThat(result).isEqualTo(EpgRunResult.Deferred(RefreshTrigger.FIRST_RUN, deferrals = 0))
        assertThat(runner.calls).isEqualTo(0)
        val event = logger.fields(EventCodes.WORK_RUN)
        assertThat(event["decision"]).isEqualTo("DEFER")
        assertThat(event["reason"]).isEqualTo(EpgRefreshPolicy.REASON_CATALOG_EMPTY)
    }

    @Test
    fun `a stale trigger runs the pipeline and reports the coverage`() = runTest {
        val runner = FakeEpgRunner()

        val result = coordinator(runner).run(RefreshTrigger.SCHEDULED)

        assertThat(runner.calls).isEqualTo(1)
        assertThat(runner.lastOptions?.respectPlayback).isTrue()
        assertThat(result).isInstanceOf(EpgRunResult.Completed::class.java)
        val report = (result as EpgRunResult.Completed).report
        assertThat(report.providers).isEqualTo(4)
        assertThat(report.coverage.matched).isEqualTo(153)

        val event = logger.fields(EventCodes.WORK_RUN)
        assertThat(event["result"]).isEqualTo("success")
        assertThat(event["programmes"]).isEqualTo(177_447)
        assertThat(event["coverageMatched"]).isEqualTo(153)
        assertThat(event["interrupted"]).isNull()
    }

    @Test
    fun `a fresh dataset is skipped without touching the pipeline`() = runTest {
        status.set(lastFetchAtMs = clock.nowMs() - 60_000L)
        val runner = FakeEpgRunner()

        val result = coordinator(runner).run(RefreshTrigger.SCHEDULED)

        assertThat(result).isEqualTo(EpgRunResult.Skipped(EpgRefreshPolicy.REASON_FRESH))
        assertThat(runner.calls).isEqualTo(0)
        assertThat(logger.fields(EventCodes.WORK_RUN)["decision"]).isEqualTo("SKIP")
    }

    @Test
    fun `a schedule while the TV is playing defers without spending any network`() = runTest {
        playing = true
        val runner = FakeEpgRunner()

        val result = coordinator(runner).run(RefreshTrigger.SCHEDULED, deferrals = 0)

        assertThat(result).isEqualTo(EpgRunResult.Deferred(RefreshTrigger.SCHEDULED, deferrals = 0))
        assertThat(runner.calls).isEqualTo(0)
        val event = logger.fields(EventCodes.WORK_RUN)
        assertThat(event["decision"]).isEqualTo("DEFER")
        assertThat(event["reason"]).isEqualTo(EpgRefreshPolicy.REASON_PLAYING)
        assertThat(event["playing"]).isEqualTo(true)
    }

    @Test
    fun `after the deferral cap the run happens even while playing`() = runTest {
        playing = true
        val runner = FakeEpgRunner()

        val result = coordinator(runner).run(
            RefreshTrigger.SCHEDULED,
            deferrals = EpgRefreshPolicy.MAX_DEFERRALS,
        )

        assertThat(result).isInstanceOf(EpgRunResult.Completed::class.java)
        assertThat(runner.calls).isEqualTo(1)
    }

    @Test
    fun `the user's button runs even while playing, and is not stopped by freshness`() = runTest {
        playing = true
        status.set(lastFetchAtMs = clock.nowMs())
        val runner = FakeEpgRunner()

        val result = coordinator(runner).run(RefreshTrigger.MANUAL)

        assertThat(result).isInstanceOf(EpgRunResult.Completed::class.java)
        assertThat(runner.calls).isEqualTo(1)
    }

    @Test
    fun `a pipeline that throws is reported as a failure, not as a crash`() = runTest {
        val runner = FakeEpgRunner(error = IllegalStateException("boom"))

        val result = coordinator(runner).run(RefreshTrigger.FIRST_RUN)

        assertThat(result).isEqualTo(EpgRunResult.Failed("IllegalStateException"))
        val event = logger.fields(EventCodes.WORK_RUN)
        assertThat(event["result"]).isEqualTo("failed")
        assertThat(event["reason"]).isEqualTo("IllegalStateException")
    }

    @Test
    fun `a pipeline that overruns the budget is reported as a budget failure`() = runTest {
        val runner = FakeEpgRunner(delayMs = 5_000L)

        val result = coordinator(runner).run(RefreshTrigger.FIRST_RUN, budgetMs = 1_000L)

        assertThat(result).isEqualTo(EpgRunResult.Failed(EpgRefreshCoordinator.REASON_BUDGET))
        assertThat(logger.fields(EventCodes.WORK_RUN)["reason"]).isEqualTo("budget_exceeded")
    }

    @Test
    fun `a run that stopped because playback started is a success with an interruption`() = runTest {
        val runner = FakeEpgRunner(
            result = ilab.iptv.player.core.common.AppResult.Ok(
                FakeEpgRunner.report(providers = 1, interrupted = "playback_priority"),
            ),
        )

        val result = coordinator(runner).run(RefreshTrigger.SCHEDULED)

        val report = (result as EpgRunResult.Completed).report
        assertThat(report.interrupted).isEqualTo("playback_priority")
        val event = logger.fields(EventCodes.WORK_RUN)
        assertThat(event["result"]).isEqualTo("interrupted")
        assertThat(event["interrupted"]).isEqualTo("playback_priority")
    }

    @Test
    fun `the default budget is ten minutes, well above the measured run`() {
        assertThat(ilab.iptv.player.core.domain.refresh.EpgRefreshBudget.DEFAULT_BUDGET_MS)
            .isEqualTo(10L * 60_000L)
    }
}
