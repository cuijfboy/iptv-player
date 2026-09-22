package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Card NEW-004, requirement ④: "失败 / 取消路径不产生死循环".
 *
 * The loop the card is worried about is not in the decision — it is the *mark*. If a run that ended
 * any way other than a process death left the flag set, [RefreshReclaim] would see an interruption on
 * every later trigger and keep replacing a perfectly ordinary job. So what is asserted here is the
 * one property that keeps the flag honest: the mark is set for exactly the span of the run and cleared
 * on the way out of *every* ending.
 */
class RefreshRunRecordTest {

    @Test
    fun `a run that finishes normally is marked in flight and then concluded`() = runTest {
        val ledger = FakeRefreshRunLedger()

        val answer = recordingRefreshRun(ledger) { "done" }

        assertThat(answer).isEqualTo("done")
        assertThat(ledger.events).containsExactly("started", "concluded").inOrder()
        assertThat(ledger.unconcluded).isFalse()
    }

    @Test
    fun `a run that throws a failure is still concluded`() = runTest {
        val ledger = FakeRefreshRunLedger()

        val thrown = runCatching { recordingRefreshRun(ledger) { throw IllegalStateException("boom") } }

        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(ledger.events).containsExactly("started", "concluded").inOrder()
        assertThat(ledger.unconcluded).isFalse()
    }

    @Test
    fun `a cancelled run is concluded too - a stop is not an interruption`() = runTest {
        // This is the wizard's 中断 button, a dropped constraint and a `REPLACE` all at once.
        val ledger = FakeRefreshRunLedger()

        val thrown = runCatching { recordingRefreshRun(ledger) { throw CancellationException("cancelled") } }

        assertThat(thrown.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(ledger.events).containsExactly("started", "concluded").inOrder()
        assertThat(ledger.unconcluded).isFalse()
    }

    @Test
    fun `only a run that never reaches its end leaves the mark behind`() {
        // The process death: the mark was written, nothing else ran. This is the *only* state
        // `RefreshReclaim` reads as "replace the queued job", which is the whole contract.
        val ledger = FakeRefreshRunLedger(unconcluded = true)

        assertThat(ledger.isRunUnconcluded()).isTrue()
        assertThat(RefreshReclaim.plan(ExistingRefreshRun(QueuedRefreshState.QUEUED), ledger.isRunUnconcluded()))
            .isEqualTo(RefreshEnqueuePolicy.REPLACE)
    }
}
