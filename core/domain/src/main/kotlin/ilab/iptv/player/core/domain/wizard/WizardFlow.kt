package ilab.iptv.player.core.domain.wizard

/**
 * The three steps of the first-run wizard (docs/04 P2-9 item 2) plus the terminal state.
 *
 * The order is the promise the card makes: ① 选源 → ② 更新 → ③ 开看, i.e. "new user sees a picture
 * within three steps". [FINISHED] is not a screen — it is the state in which the wizard hands over
 * to the channel list.
 */
enum class WizardStep { SOURCE, UPDATE, WATCH, FINISHED }

/**
 * What the wizard has decided so far, as data. Kept free of Android and of the repository, so the
 * advance/skip/back table below is a unit test instead of a click-through.
 *
 * [skipped] is the reason the dispatch insists on "跳过后的状态要明确": a skipped step is recorded,
 * not forgotten, and the screens read it back to say what the user is about to get (a possibly empty
 * channel list) rather than pretending the step happened.
 */
data class WizardState(
    val step: WizardStep = WizardStep.SOURCE,
    val skipped: Set<WizardStep> = emptySet(),
) {
    val sourceSkipped: Boolean get() = WizardStep.SOURCE in skipped
    val updateSkipped: Boolean get() = WizardStep.UPDATE in skipped
    val watchSkipped: Boolean get() = WizardStep.WATCH in skipped

    /** 1-based number of the current step for the "第 N 步 / 共 3 步" line; null once finished. */
    val stepNumber: Int? get() = WizardFlow.numberOf(step)
}

/**
 * The wizard's step machine (docs/04 P2-9 items 2 and 3: "每步都能跳过" + "返回键层级一致").
 *
 * Three transitions, deliberately separate:
 * - [next] — the step was done; advance;
 * - [skip] — the user declined the step; advance **and record it**;
 * - [back] — the remote's BACK: one level up, where one level up from the first step is *leaving*
 *   the wizard (`null`), which is what makes BACK inside the wizard a consistent two-level walk
 *   instead of an exit from anywhere.
 *
 * `FINISHED` has no next/skip: the caller has already handed over to the channel list.
 */
object WizardFlow {

    /** How many steps the wizard shows ("新用户 3 步内看到画面"). */
    const val TOTAL_STEPS: Int = 3

    /** The 1-based display number of [step], or null for [WizardStep.FINISHED]. */
    fun numberOf(step: WizardStep): Int? = when (step) {
        WizardStep.SOURCE -> 1
        WizardStep.UPDATE -> 2
        WizardStep.WATCH -> 3
        WizardStep.FINISHED -> null
    }

    private val order: List<WizardStep> =
        listOf(WizardStep.SOURCE, WizardStep.UPDATE, WizardStep.WATCH)

    /** The step after [step], or [WizardStep.FINISHED] after the last one. */
    fun after(step: WizardStep): WizardStep = when (step) {
        WizardStep.SOURCE -> WizardStep.UPDATE
        WizardStep.UPDATE -> WizardStep.WATCH
        WizardStep.WATCH, WizardStep.FINISHED -> WizardStep.FINISHED
    }

    /** The step before [step], or null for the first step (BACK leaves the wizard). */
    fun before(step: WizardStep): WizardStep? = when (step) {
        WizardStep.SOURCE -> null
        WizardStep.UPDATE -> WizardStep.SOURCE
        WizardStep.WATCH -> WizardStep.UPDATE
        WizardStep.FINISHED -> WizardStep.WATCH
    }

    /** The step was done. */
    fun next(state: WizardState): WizardState = state.copy(step = after(state.step))

    /**
     * The step was declined. The step is remembered as skipped; a later `next` on the same step
     * (the user went back and changed their mind) clears it again, so the state never claims a step
     * was skipped after the user actually did it.
     */
    fun skip(state: WizardState): WizardState = state.copy(
        step = after(state.step),
        skipped = state.skipped + state.step,
    )

    /** Result of BACK: the previous state, or null when BACK means "leave the wizard". */
    fun back(state: WizardState): WizardState? {
        val previous = before(state.step) ?: return null
        return state.copy(step = previous, skipped = state.skipped - previous)
    }

    /** Index of [step] in the flow order (0-based); [WizardStep.FINISHED] is past the end. */
    fun indexOf(step: WizardStep): Int = order.indexOf(step)
}
