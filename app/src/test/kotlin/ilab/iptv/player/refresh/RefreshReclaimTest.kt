package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Card NEW-004 (docs/05-过程记录/67): the enqueue decision, asserted as data.
 *
 * The QA round's repro is "a manual refresh the process death interrupted comes back as a WorkManager
 * job with `Minimum latency +29m27s` (`Backoff: policy=0 initial=+30m0s`), and the 更新 step's
 * automatic re-trigger changes nothing". The first test below *is* that repro, modelled on the one
 * `ExistingWorkPolicy` rule that caused it: `KEEP` leaves a job that already exists alone.
 */
class RefreshReclaimTest {

    /** The one rule of `enqueueUniqueWork` this card turns on, so the repro is a real reproduction. */
    private class ManualQueue(private val backoffOfInterruptedRunMs: Long = 30L * 60_000L) {

        /** The delay the queued job would still have to wait; null once it has run. */
        var pendingDelayMs: Long? = null
            private set

        /** What the interrupted run left behind: ENQUEUED, waiting out its own backoff. */
        fun seedInterruptedRunInBackoff() {
            pendingDelayMs = backoffOfInterruptedRunMs
        }

        fun enqueue(spec: RefreshWorkSpec, policy: RefreshEnqueuePolicy) {
            when (policy) {
                // "there is already a job under this name — drop the new request".
                RefreshEnqueuePolicy.KEEP -> if (pendingDelayMs == null) pendingDelayMs = spec.initialDelayMs
                // "cancel it and enqueue this one in its place": a fresh request, its own initial delay.
                RefreshEnqueuePolicy.REPLACE -> pendingDelayMs = spec.initialDelayMs
            }
        }
    }

    private fun plan(existing: ExistingRefreshRun?, unconcluded: Boolean) =
        RefreshReclaim.plan(existing, unconcluded)

    @Test
    fun `reproduction - the re-trigger of an interrupted run used to be swallowed by the backoff`() {
        val queue = ManualQueue()
        queue.seedInterruptedRunInBackoff()

        // The old code always asked for KEEP. The job is already there, so the request is dropped and
        // the run keeps waiting ~30 min — the QA observation, as an assertion.
        queue.enqueue(RefreshWorkSpec.immediate(), RefreshEnqueuePolicy.KEEP)
        assertThat(queue.pendingDelayMs).isEqualTo(30L * 60_000L)

        // The fix: with the interruption on the ledger the queued job is replaced, so the request
        // lands as a fresh immediate run instead of behind the dead run's backoff.
        queue.enqueue(
            RefreshWorkSpec.immediate(),
            plan(ExistingRefreshRun(QueuedRefreshState.QUEUED), unconcluded = true),
        )
        assertThat(queue.pendingDelayMs).isEqualTo(0L)
    }

    @Test
    fun `a queued job whose last run was interrupted is replaced`() {
        assertThat(plan(ExistingRefreshRun(QueuedRefreshState.QUEUED), unconcluded = true))
            .isEqualTo(RefreshEnqueuePolicy.REPLACE)
    }

    @Test
    fun `a queued job with no interruption on record keeps its backoff`() {
        // Rule 3: this is a run that ended and asked WorkManager for a retry itself.
        assertThat(plan(ExistingRefreshRun(QueuedRefreshState.QUEUED), unconcluded = false))
            .isEqualTo(RefreshEnqueuePolicy.KEEP)
    }

    @Test
    fun `a running job is never cancelled by a re-trigger`() {
        // Rule 2: a live run stays a no-op, even if the ledger says a run is in flight (it is: this
        // one). Cancelling it would throw away the work the run has already done.
        assertThat(plan(ExistingRefreshRun(QueuedRefreshState.RUNNING), unconcluded = true))
            .isEqualTo(RefreshEnqueuePolicy.KEEP)
        assertThat(plan(ExistingRefreshRun(QueuedRefreshState.RUNNING), unconcluded = false))
            .isEqualTo(RefreshEnqueuePolicy.KEEP)
    }

    @Test
    fun `nothing queued and a finished job both start a fresh run`() {
        // `KEEP` with nothing pending is a plain enqueue; with a finished job the name is free, so it
        // is too. Neither is a `REPLACE`, which is what keeps the normal path byte-for-byte.
        assertThat(plan(null, unconcluded = false)).isEqualTo(RefreshEnqueuePolicy.KEEP)
        assertThat(plan(null, unconcluded = true)).isEqualTo(RefreshEnqueuePolicy.KEEP)
        assertThat(plan(ExistingRefreshRun(QueuedRefreshState.FINISHED), unconcluded = false))
            .isEqualTo(RefreshEnqueuePolicy.KEEP)
        assertThat(plan(ExistingRefreshRun(QueuedRefreshState.FINISHED), unconcluded = true))
            .isEqualTo(RefreshEnqueuePolicy.KEEP)
    }
}
