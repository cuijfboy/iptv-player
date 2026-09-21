package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.domain.refresh.PlaybackAvoidancePolicy
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.RefreshInterruption
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshProgress
import ilab.iptv.player.core.model.RefreshTrigger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P2-5 items 3 and 4: the avoidance decision in front of the pipeline, the progress fan-out, and the
 * failure/cancellation contracts the worker relies on.
 */
class RefreshRunCoordinatorTest {

    private val logger = RecordingLogger()

    private fun coordinator(runner: RefreshRunner, playing: Boolean) = RefreshRunCoordinator(
        runner = runner,
        logger = logger,
        policy = PlaybackAvoidancePolicy(),
        playback = { playing },
        clock = FakeClock(),
    )

    @Test
    fun `a scheduled run while playing is deferred and never touches the pipeline`() = runTest {
        val runner = FakeRefreshRunner(frames = listOf(frame(RefreshPhase.DONE)))
        val result = coordinator(runner, playing = true).run(RefreshTrigger.SCHEDULED, deferrals = 0)

        assertThat(result).isInstanceOf(RefreshRunResult.Deferred::class.java)
        assertThat(runner.calls).isEqualTo(0)
        assertThat(logger.count(EventCodes.SERVICE_REFRESH_STOP)).isEqualTo(1)
    }

    @Test
    fun `a scheduled run with the deferrals used up runs anyway`() = runTest {
        val runner = FakeRefreshRunner(frames = listOf(frame(RefreshPhase.DONE, ok = 3, total = 3)))
        val result = coordinator(runner, playing = true).run(
            RefreshTrigger.SCHEDULED,
            deferrals = PlaybackAvoidancePolicy.MAX_DEFERRALS,
        )

        assertThat(result).isInstanceOf(RefreshRunResult.Completed::class.java)
        assertThat(runner.calls).isEqualTo(1)
    }

    @Test
    fun `a user-facing run while playing runs with playback respected`() = runTest {
        val runner = FakeRefreshRunner(frames = listOf(frame(RefreshPhase.DONE)))
        val result = coordinator(runner, playing = true).run(RefreshTrigger.MANUAL)

        assertThat(result).isInstanceOf(RefreshRunResult.Completed::class.java)
        assertThat(runner.lastOptions?.trigger).isEqualTo(RefreshTrigger.MANUAL)
        // The pipeline halves concurrency when playback is on; the coordinator must not turn that off.
        assertThat(runner.lastOptions?.respectPlayback).isTrue()
    }

    @Test
    fun `every progress frame reaches the notification and the last one is the answer`() = runTest {
        val frames = listOf(
            frame(RefreshPhase.FETCH, done = 1, total = 1),
            frame(RefreshPhase.SHALLOW, done = 3, total = 3, ok = 3),
            frame(RefreshPhase.DONE, done = 3, total = 3, ok = 3, elapsedMs = 1_200),
        )
        val seen = mutableListOf<RefreshProgress>()
        val result = coordinator(FakeRefreshRunner(frames = frames), playing = false)
            .run(RefreshTrigger.SCHEDULED, onProgress = { seen += it })

        assertThat(seen.map { it.phase }).containsExactlyElementsIn(frames.map { it.phase }).inOrder()
        val completed = result as RefreshRunResult.Completed
        assertThat(completed.last.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(completed.interrupted).isNull()
    }

    @Test
    fun `a budget interruption is reported, not retried inside the run`() = runTest {
        val interruption = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.DEEP)
        val result = coordinator(
            FakeRefreshRunner(frames = listOf(frame(RefreshPhase.DONE, interrupted = interruption))),
            playing = false,
        ).run(RefreshTrigger.SCHEDULED)

        assertThat((result as RefreshRunResult.Completed).interrupted).isEqualTo(interruption)
    }

    @Test
    fun `a throwing pipeline becomes a Failed result the worker can classify`() = runTest {
        val result = coordinator(FakeRefreshRunner(error = IllegalStateException("boom")), playing = false)
            .run(RefreshTrigger.SCHEDULED)

        assertThat((result as RefreshRunResult.Failed).error).isInstanceOf(IllegalStateException::class.java)
        assertThat(logger.count(EventCodes.SRC_REFRESH_DONE)).isEqualTo(1)
    }

    @Test
    fun `cancellation is rethrown, never reported as a failure`() = runTest {
        val thrown = runCatching {
            coordinator(FakeRefreshRunner(error = CancellationException("stopped")), playing = false)
                .run(RefreshTrigger.SCHEDULED)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        // The failure log is for real failures only: a cancelled run is a stop signal, not evidence
        // that anything is broken (docs/02 §4.5 C5).
        assertThat(logger.count(EventCodes.SRC_REFRESH_DONE)).isEqualTo(0)
    }

    @Test
    fun `respectPlayback false keeps a scheduled run going while playing`() = runTest {
        val runner = FakeRefreshRunner(frames = listOf(frame(RefreshPhase.DONE)))
        val result = coordinator(runner, playing = true)
            .run(RefreshTrigger.SCHEDULED, deferrals = 0, respectPlayback = false)

        assertThat(result).isInstanceOf(RefreshRunResult.Completed::class.java)
        assertThat(runner.lastOptions?.respectPlayback).isFalse()
    }
}
