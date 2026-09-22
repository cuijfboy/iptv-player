package ilab.iptv.player.feature.wizard

import ilab.iptv.player.core.domain.wizard.BuiltInSourceInfo
import ilab.iptv.player.core.domain.wizard.WizardStep

/**
 * The screen the wizard shows right now (NEW-1). Three step panels plus the 选源 step's read-only
 * 二级页 for the 17 built-in sources — the page the step-1 summary row opens instead of printing all
 * 17 lines on the decision panel.
 */
enum class WizardPanel { SOURCE, UPDATE, WATCH, SOURCES }

/** Every button the wizard can put on a panel, named for the acceptance table rather than for the id. */
enum class WizardAction {
    CONTINUE, IMPORT, SUBSCRIBE, SKIP,
    UPDATE_START, UPDATE_CANCEL, UPDATE_SKIP,
    WATCH_OPEN, WATCH_IMPORT, WATCH_SKIP,
    SOURCES_BACK,
}

/**
 * Which panel is on screen, which buttons it shows, and where the remote lands — as data, so the
 * layout rules NEW-1 is about are unit tests instead of a device.
 *
 * WHY THIS IS A MODEL AND NOT THREE `visibility = ...` LINES: NEW-1 happened because a *view* decision
 * (17 lines of text above four buttons) was invisible to every test the card had. The two rules that
 * follow from the fix are stated here once and pinned by `WizardPanelLayoutTest`:
 *
 * 1. **the long list is never on a decision panel.** The 17 built-ins live under [WizardPanel.SOURCES];
 *    the step panels carry a one-line summary ([WizardSourceSummary.rowLabel]) that links there;
 * 2. **the primary button is never inside the scrolling region.** The buttons live in
 *    `wizard_footer` — a `wrap_content` sibling of the one weighted child (`wizard_body`, which holds
 *    the panels' `ScrollView`s) — so they are measured before the weight is resolved and stay on
 *    screen at every panel height. [fixedChromeDp] is the height that costs, and [fitsOnFirstScreen]
 *    plus [viewportDp] check it against the two screens the card names.
 */
object WizardPanels {

    /** The panel for a step, or null when the wizard has handed over ([WizardStep.FINISHED]). */
    fun of(step: WizardStep, sourcesOpen: Boolean): WizardPanel? = when {
        step == WizardStep.SOURCE && sourcesOpen -> WizardPanel.SOURCES
        step == WizardStep.SOURCE -> WizardPanel.SOURCE
        step == WizardStep.UPDATE -> WizardPanel.UPDATE
        step == WizardStep.WATCH -> WizardPanel.WATCH
        else -> null
    }

    /**
     * The panel's buttons **in D-pad order**: an outer list of rows, each row laid out left to right.
     *
     * Every panel puts its **primary action on its own row** and the side doors on the row below, for
     * the same reason twice: it keeps the action the card promises ("sees a picture within three
     * steps") visually first, and it keeps the footer to two rows. Four full-width rows at 48 dp each
     * would not leave a scrolling region on a 720p panel — which is NEW-1's arithmetic again — and one
     * row of three would not fit 进入频道表（按 OK 播放）at 640 dp. The width budget is checked by
     * `WizardPanelLayoutTest` against the labels in `strings.xml`.
     */
    fun footerRows(panel: WizardPanel): List<List<WizardAction>> = when (panel) {
        WizardPanel.SOURCE -> listOf(
            listOf(WizardAction.CONTINUE),
            listOf(WizardAction.IMPORT, WizardAction.SUBSCRIBE, WizardAction.SKIP),
        )

        WizardPanel.UPDATE -> listOf(
            listOf(WizardAction.UPDATE_START),
            listOf(WizardAction.UPDATE_CANCEL, WizardAction.UPDATE_SKIP),
        )

        WizardPanel.WATCH -> listOf(
            listOf(WizardAction.WATCH_OPEN),
            listOf(WizardAction.WATCH_IMPORT, WizardAction.WATCH_SKIP),
        )

        WizardPanel.SOURCES -> listOf(listOf(WizardAction.SOURCES_BACK))
    }

    /** Every button on the panel, flattened. */
    fun actionsOf(panel: WizardPanel): List<WizardAction> = footerRows(panel).flatten()

    /** The button the remote lands on when the panel opens: the first one, top left. */
    fun primaryOf(panel: WizardPanel): WizardAction = footerRows(panel).first().first()

    // ---- the first-screen budget -------------------------------------------------------------

    /** `activity_wizard.xml`'s root `paddingVertical`. */
    const val VERTICAL_PADDING_DP: Int = 24

    /** `wizard_header`'s `minHeight` — the "第 N 步 / 共 3 步" line is one text line, never more. */
    const val HEADER_MIN_HEIGHT_DP: Int = 36

    /** `wizard_hint`'s `minHeight` — the BACK hint under the header. */
    const val HINT_MIN_HEIGHT_DP: Int = 24

    /** The gap between the header block and the panel, and between the panel's scroll and its footer. */
    const val SECTION_GAP_DP: Int = 12

    /** Every button's `minHeight` (docs/02 §8.2: 10-foot minimum target). */
    const val BUTTON_MIN_HEIGHT_DP: Int = 48

    /** The gap between the buttons inside a footer row. */
    const val BUTTON_GAP_DP: Int = 8

    /** The tallest screen the card names as a floor: 1280×720 at the device's 320 density. */
    const val SCREEN_HEIGHT_720P_DP: Int = 360

    /** The same screen's width: 1280 ÷ 2. */
    const val SCREEN_WIDTH_720P_DP: Int = 640

    /** `activity_wizard.xml`'s root `paddingHorizontal`. */
    const val HORIZONTAL_PADDING_DP: Int = 48

    /** 1920×1080 at the same density. */
    const val SCREEN_HEIGHT_1080P_DP: Int = 540

    /**
     * The smallest scrolling description area that is still usable: the 选源 summary row (48 dp) plus
     * one line of status. A panel that cannot leave this much is a panel whose description has nowhere
     * to go, even though its buttons are on screen.
     */
    const val MIN_VIEWPORT_DP: Int = 96

    /**
     * The height of everything that is **not** the scrolling description: the root padding, the
     * header, the hint, the gap under the header block, and the footer's rows. It is deliberately
     * measured from constants that `activity_wizard.xml` reads from `values/dimens.xml`, and
     * `WizardPanelLayoutTest` walks the XML's DOM to prove the buttons these numbers count really are
     * outside its `ScrollView`s.
     */
    fun fixedChromeDp(rows: Int): Int =
        2 * VERTICAL_PADDING_DP +
            HEADER_MIN_HEIGHT_DP +
            HINT_MIN_HEIGHT_DP +
            SECTION_GAP_DP +
            (if (rows > 0) BUTTON_GAP_DP else 0) +
            rows * BUTTON_MIN_HEIGHT_DP +
            (rows - 1).coerceAtLeast(0) * BUTTON_GAP_DP

    fun fixedChromeDp(panel: WizardPanel): Int = fixedChromeDp(footerRows(panel).size)

    /** The height left for the panel's scrolling description at [screenHeightDp]. */
    fun viewportDp(panel: WizardPanel, screenHeightDp: Int): Int =
        screenHeightDp - fixedChromeDp(panel)

    /** True when [panel]'s primary button is laid out on the first screen at [screenHeightDp]. */
    fun fitsOnFirstScreen(panel: WizardPanel, screenHeightDp: Int): Boolean =
        fixedChromeDp(panel) <= screenHeightDp

    /** The width a row of [buttonsInRow] equal buttons gets inside [screenWidthDp]. */
    fun buttonWidthDp(buttonsInRow: Int, screenWidthDp: Int): Int {
        val content = screenWidthDp - 2 * HORIZONTAL_PADDING_DP
        return (content - (buttonsInRow - 1).coerceAtLeast(0) * BUTTON_GAP_DP) / buttonsInRow
    }
}

/**
 * The 选源 step's one-line summary and the 二级页's rows, as data.
 *
 * The step used to print all 17 built-ins as 17 text lines; that is what pushed the buttons off the
 * bottom (NEW-1). The list did not lose information — it moved one level down
 * ([WizardPanel.SOURCES]) — so this object keeps the two shapes together: what the summary row says
 * ([summaryOf]) and what the page it opens lists ([detailRows]). Nothing here touches Android and the
 * wording stays in `strings.xml`, so the *decisions* are unit tests and the prose is still a resource.
 *
 * **查看, not 查看/调整**: there is still no per-source switch to write to (doc 44 §3.2 — the built-in
 * catalogue is a compile-time `@IntoSet`, and the `source` table holds only the user's subscriptions),
 * so the entry says 查看清单 and does not promise a toggle it cannot honour. The page says so in words
 * instead of drawing a checkbox that would not save.
 */
object WizardSourceSummary {

    /**
     * What the 选源 summary row reports. [count] is the catalogue size; [loaded] is false while the
     * catalogue has not been read yet (the read model starts empty), so the row can say "读取中"
     * instead of a confident "已启用 0 个".
     */
    data class Summary(val count: Int) {
        val loaded: Boolean get() = count > 0
    }

    fun summaryOf(builtIns: List<BuiltInSourceInfo>): Summary = Summary(count = builtIns.size)

    /**
     * The 二级页's rows, one per built-in, **in catalogue order** — the same order the update will
     * fetch them in, which is the only thing the page is for.
     */
    fun detailRows(builtIns: List<BuiltInSourceInfo>): List<String> = builtIns.map { it.label }
}
