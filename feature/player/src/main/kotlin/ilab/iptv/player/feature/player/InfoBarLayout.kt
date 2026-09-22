package ilab.iptv.player.feature.player

/**
 * The info bar's geometry decision (BUG-017, S2).
 *
 * P3-3 added four controls (画幅 / 音轨 / 字幕 / 过扫描) to a bar that had room for one, so the channel
 * name, the quality line and the now/next line shared a single horizontal row with them and were
 * squeezed to **209 px** — the acceptance report (§2 of `docs/05-过程记录/42-VERIFY1真机验证报告.md`)
 * read the ellipsis straight out of the `uiautomator` bounds of `info_name`. The fix is a two-row bar:
 * the logo and the whole text column own the first row, the four controls share their own row below.
 *
 * This object holds the numbers that layout rests on and the arithmetic that proves it, as plain
 * Kotlin so a test can pin the acceptance number ("the text column keeps its floor at 1280×720")
 * instead of someone reading a screenshot. `res/values/dimens.xml` mirrors the same numbers and
 * `InfoBarLayoutTest` reads the XML back so the two cannot drift.
 *
 * Density note: the TV reported 1920×1080 at xhdpi (2.0), i.e. **960 dp**, which is also what the
 * emulator's bounds put at a 240 px left edge for the 120 dp logo+padding+margin. 1280×720 at the same
 * density is **640 dp**, and that is the narrow layout this bar must survive.
 */
object InfoBarLayout {

    /** Reference widths in dp for the two acceptance layouts (see the density note above). */
    const val WIDTH_1080P_DP = 960
    const val WIDTH_720P_DP = 640

    /**
     * The floor the text column may not go below. Below this even a short channel name plus a one-line
     * title starts to clip at the sizes the bar uses (name 30sp, now/next 18sp).
     */
    const val TEXT_COLUMN_MIN_WIDTH_DP = 360

    // --- geometry mirrored by res/values/dimens.xml ---

    const val BAR_H_PADDING_DP = 32
    const val LOGO_SIZE_DP = 64
    const val TEXT_MARGIN_START_DP = 24
    const val CONTROLS_MARGIN_TOP_DP = 16
    const val CONTROL_COUNT = 4
    const val CONTROL_MIN_WIDTH_DP = 96
    const val CONTROL_MARGIN_START_DP = 8
    const val CONTROL_H_PADDING_DP = 6
    const val CONTROL_TEXT_SP = 18

    /** Width of the first row's text column when the four controls sit on their own row. */
    fun textColumnWidthDp(screenWidthDp: Int): Int =
        screenWidthDp - 2 * BAR_H_PADDING_DP - LOGO_SIZE_DP - TEXT_MARGIN_START_DP

    /** Width available to the row of four controls (bar width minus the bar's horizontal padding). */
    fun controlsRowWidthDp(screenWidthDp: Int): Int = screenWidthDp - 2 * BAR_H_PADDING_DP

    /** One control's width: the row is split evenly, margins are taken out first (equal weights). */
    fun controlWidthDp(screenWidthDp: Int): Int =
        (controlsRowWidthDp(screenWidthDp) - CONTROL_COUNT * CONTROL_MARGIN_START_DP) / CONTROL_COUNT

    /** The text box inside one control (the button minus its own horizontal padding). */
    fun controlTextWidthDp(screenWidthDp: Int): Int =
        controlWidthDp(screenWidthDp) - 2 * CONTROL_H_PADDING_DP

    /** The width the four controls need if they shared the text row (the P3-3 arrangement). */
    fun inlineControlsWidthDp(): Int =
        CONTROL_COUNT * (CONTROL_MIN_WIDTH_DP + CONTROL_MARGIN_START_DP)

    /** What the text column would be left with if the controls stayed on the same row. */
    fun inlineTextColumnWidthDp(screenWidthDp: Int): Int =
        textColumnWidthDp(screenWidthDp) - inlineControlsWidthDp()

    /** True when the two-row layout gives the text column its floor at this width. */
    fun textColumnKeepsFloor(screenWidthDp: Int): Boolean =
        textColumnWidthDp(screenWidthDp) >= TEXT_COLUMN_MIN_WIDTH_DP

    /**
     * The wrap rule: on one row the four controls cannot share the line with a text column that still
     * meets [TEXT_COLUMN_MIN_WIDTH_DP]. This is **false** at 640 dp (the 1280×720 layout), which is the
     * whole reason the bar is two rows — at 960 dp a shared row would *just* fit (392 dp ≥ 360), so the
     * rule is width-driven, not a taste call.
     */
    fun inlinePlacementFits(screenWidthDp: Int): Boolean =
        inlineTextColumnWidthDp(screenWidthDp) >= TEXT_COLUMN_MIN_WIDTH_DP

    /**
     * A conservative glyph-width model, good enough to prove a floor without a font on the test host:
     * a full-width glyph (CJK, full-width punctuation) is 1 em, an ASCII glyph about 0.55 em.
     */
    fun textWidthDp(text: String, textSizeSp: Int): Int {
        val em = text.sumOf { if (it.code >= 0x2E80) 1.0 else 0.55 }
        return Math.round(em * textSizeSp).toInt()
    }
}
