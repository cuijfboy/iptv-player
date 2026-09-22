package ilab.iptv.player.core.domain.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * docs/04 P2-9 items 2–3: the step machine, the skip branches and the BACK hierarchy.
 *
 * Every transition is asserted from every step, because "每步都能跳过" and "返回键层级一致" are
 * statements about *all* the steps, not about the first one.
 */
class WizardFlowTest {

    @Test
    fun `the wizard has exactly three steps, numbered from one`() {
        assertThat(WizardFlow.TOTAL_STEPS).isEqualTo(3)
        assertThat(WizardFlow.numberOf(WizardStep.SOURCE)).isEqualTo(1)
        assertThat(WizardFlow.numberOf(WizardStep.UPDATE)).isEqualTo(2)
        assertThat(WizardFlow.numberOf(WizardStep.WATCH)).isEqualTo(3)
        assertThat(WizardFlow.numberOf(WizardStep.FINISHED)).isNull()
    }

    @Test
    fun `continue walks the three steps in order`() {
        var state = WizardState()
        assertThat(state.step).isEqualTo(WizardStep.SOURCE)

        state = WizardFlow.next(state)
        assertThat(state.step).isEqualTo(WizardStep.UPDATE)

        state = WizardFlow.next(state)
        assertThat(state.step).isEqualTo(WizardStep.WATCH)

        state = WizardFlow.next(state)
        assertThat(state.step).isEqualTo(WizardStep.FINISHED)

        // The last step has nowhere further to go; the screen has already handed over by then.
        assertThat(WizardFlow.next(state).step).isEqualTo(WizardStep.FINISHED)
        assertThat(state.stepNumber).isNull()
    }

    @Test
    fun `every step can be skipped and the skip is recorded`() {
        val afterSourceSkip = WizardFlow.skip(WizardState())
        assertThat(afterSourceSkip.step).isEqualTo(WizardStep.UPDATE)
        assertThat(afterSourceSkip.sourceSkipped).isTrue()

        val afterUpdateSkip = WizardFlow.skip(afterSourceSkip)
        assertThat(afterUpdateSkip.step).isEqualTo(WizardStep.WATCH)
        assertThat(afterUpdateSkip.sourceSkipped).isTrue()
        assertThat(afterUpdateSkip.updateSkipped).isTrue()

        // 开看 is the hand-over step: skipping it is the same advance, and it is remembered too.
        val afterWatchSkip = WizardFlow.skip(afterUpdateSkip)
        assertThat(afterWatchSkip.step).isEqualTo(WizardStep.FINISHED)
        assertThat(afterWatchSkip.watchSkipped).isTrue()
    }

    @Test
    fun `going back after a skip does not leave the step marked as skipped`() {
        // The user skipped 选源, went on to 更新, pressed BACK and then actually did it: the state
        // must not keep claiming the step was skipped, or the next steps would report a half-truth.
        val skipped = WizardFlow.skip(WizardState())
        val back = WizardFlow.back(skipped)

        assertThat(back).isNotNull()
        assertThat(back!!.step).isEqualTo(WizardStep.SOURCE)
        assertThat(back.sourceSkipped).isFalse()
    }

    @Test
    fun `back walks one level up from every step`() {
        assertThat(WizardFlow.back(WizardState(WizardStep.WATCH))!!.step).isEqualTo(WizardStep.UPDATE)
        assertThat(WizardFlow.back(WizardState(WizardStep.UPDATE))!!.step).isEqualTo(WizardStep.SOURCE)
    }

    @Test
    fun `back from the first step means leaving the wizard`() {
        // docs/02 §8.1 返回键层级: BACK inside the wizard is a two-level walk, and the first step's
        // BACK is the way out — the screen finishes the activity when this returns null.
        assertThat(WizardFlow.back(WizardState(WizardStep.SOURCE))).isNull()
    }

    @Test
    fun `finished still has a back, so the last step is never a dead end`() {
        assertThat(WizardFlow.back(WizardState(WizardStep.FINISHED))!!.step)
            .isEqualTo(WizardStep.WATCH)
    }
}
