package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.source.pipeline.RefreshBudget
import ilab.iptv.player.core.source.pipeline.StageProfile
import ilab.iptv.player.core.source.pipeline.StageProfiles
import org.junit.Test

class RefreshBudgetTest {

    private val shallow = StageProfile(RefreshPhase.SHALLOW, p50Ms = 1_000)

    @Test
    fun `admits while a median stage still fits`() {
        val clock = FakeClock(now = 0)
        val budget = RefreshBudget(deadlineMs = 5_000, clock = clock)
        assertThat(budget.canAdmitNext(shallow)).isTrue()
        clock.advance(4_000) // remaining 1_000 == p50
        assertThat(budget.canAdmitNext(shallow)).isTrue()
    }

    @Test
    fun `stops admitting once the remaining budget is below the median`() {
        val clock = FakeClock(now = 0)
        val budget = RefreshBudget(deadlineMs = 5_000, clock = clock)
        clock.advance(4_001)
        assertThat(budget.canAdmitNext(shallow)).isFalse()
    }

    @Test
    fun `an exhausted deadline is expired`() {
        val clock = FakeClock(now = 0)
        val budget = RefreshBudget(deadlineMs = 5_000, clock = clock)
        clock.advance(5_000)
        assertThat(budget.isExpired()).isTrue()
    }

    @Test
    fun `a batch is admitted only when every item fits`() {
        val clock = FakeClock(now = 0)
        val budget = RefreshBudget(deadlineMs = 5_000, clock = clock)
        assertThat(budget.canAdmit(count = 3, perItem = shallow)).isTrue()
        clock.advance(2_500)
        assertThat(budget.canAdmit(count = 3, perItem = shallow)).isFalse()
        assertThat(budget.canAdmit(count = 2, perItem = shallow)).isTrue()
    }

    @Test
    fun `the default profiles match the frozen stage order`() {
        assertThat(StageProfiles.ALL.map { it.phase }).containsExactly(
            RefreshPhase.FETCH, RefreshPhase.PARSE, RefreshPhase.NORMALIZE, RefreshPhase.DEDUPE,
            RefreshPhase.SHALLOW, RefreshPhase.DEEP, RefreshPhase.SCORE, RefreshPhase.SELECT,
            RefreshPhase.PERSIST,
        ).inOrder()
    }
}
