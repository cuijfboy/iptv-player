package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * BUG-017's proof. The acceptance report (§2 of `docs/05-过程记录/42-VERIFY1真机验证报告.md`) has the
 * device evidence: `info_name` measured `bounds=[240,856][449,937]` — a 209 px text column behind a
 * 1920×1080 screen, so every line on the bar ellipsised. This test is the other half of the evidence
 * the report asks for: the static size computation that says the two-row bar keeps the text column
 * above its floor at **both** layouts the card names, 1920×1080 (960 dp) and 1280×720 (640 dp).
 *
 * It also reads `res/values/dimens.xml` back and refuses to let the XML numbers drift from
 * [InfoBarLayout] — the same guard `TvFocusTokenTest` puts on the focus tokens. No Robolectric and no
 * device: the file is read from the module directory, which is the unit test's working directory.
 */
class InfoBarLayoutTest {

    private val dimens: Map<String, String> =
        parseDimens(File("src/main/res/values/dimens.xml"))

    // ---------------------------------------------------------------- the acceptance number

    @Test
    fun `the text column keeps its floor at both layouts`() {
        listOf(InfoBarLayout.WIDTH_1080P_DP, InfoBarLayout.WIDTH_720P_DP).forEach { width ->
            assertThat(InfoBarLayout.textColumnKeepsFloor(width)).isTrue()
            assertThat(InfoBarLayout.textColumnWidthDp(width))
                .isAtLeast(InfoBarLayout.TEXT_COLUMN_MIN_WIDTH_DP)
        }
        // The exact numbers the two layouts resolve to: 808 dp at 960, 488 dp at 640.
        assertThat(InfoBarLayout.textColumnWidthDp(InfoBarLayout.WIDTH_1080P_DP)).isEqualTo(808)
        assertThat(InfoBarLayout.textColumnWidthDp(InfoBarLayout.WIDTH_720P_DP)).isEqualTo(488)
    }

    @Test
    fun `the channel name and the now-next line fit the text column`() {
        val narrow = InfoBarLayout.textColumnWidthDp(InfoBarLayout.WIDTH_720P_DP)
        // The name the device report read out (`CCTV-10科教`), a longer renamed one, and the now/next
        // formats (`player_now_next_now` / `player_now_next_next`).
        assertThat(InfoBarLayout.textWidthDp("CCTV-10科教", NAME_SP)).isAtMost(narrow)
        assertThat(InfoBarLayout.textWidthDp("CCTV-13新闻频道高清", NAME_SP)).isAtMost(narrow)
        assertThat(InfoBarLayout.textWidthDp("正在播出：新闻联播", NOW_NEXT_SP)).isAtMost(narrow)
        assertThat(InfoBarLayout.textWidthDp("接下来：新闻联播特别报道", NOW_NEXT_SP)).isAtMost(narrow)
        // …and one of them would not have fitted the 209 px column the device measured (104 dp).
        assertThat(InfoBarLayout.textWidthDp("CCTV-10科教", NAME_SP)).isGreaterThan(104)
    }

    // ---------------------------------------------------------------- the wrap rule

    @Test
    fun `one row would not survive the 720p layout, which is why the bar is two rows`() {
        // At 960 dp a shared row would just fit, so the rule is width-driven rather than a taste call…
        assertThat(InfoBarLayout.inlineTextColumnWidthDp(InfoBarLayout.WIDTH_1080P_DP)).isEqualTo(392)
        assertThat(InfoBarLayout.inlinePlacementFits(InfoBarLayout.WIDTH_1080P_DP)).isTrue()
        // …but at 640 dp the controls would leave the text column 72 dp, far under the floor.
        assertThat(InfoBarLayout.inlineTextColumnWidthDp(InfoBarLayout.WIDTH_720P_DP)).isEqualTo(72)
        assertThat(InfoBarLayout.inlinePlacementFits(InfoBarLayout.WIDTH_720P_DP)).isFalse()
    }

    @Test
    fun `the four controls split their row evenly and the longest label still fits`() {
        assertThat(InfoBarLayout.controlWidthDp(InfoBarLayout.WIDTH_1080P_DP)).isEqualTo(216)
        assertThat(InfoBarLayout.controlWidthDp(InfoBarLayout.WIDTH_720P_DP)).isEqualTo(136)
        assertThat(InfoBarLayout.controlTextWidthDp(InfoBarLayout.WIDTH_720P_DP)).isEqualTo(124)

        val narrowest = InfoBarLayout.controlTextWidthDp(InfoBarLayout.WIDTH_720P_DP)
        listOf("画幅：适应", "音轨：中文", "字幕：关", "过扫描：100%").forEach { label ->
            assertThat(InfoBarLayout.textWidthDp(label, InfoBarLayout.CONTROL_TEXT_SP)).isAtMost(narrowest)
        }
    }

    // ---------------------------------------------------------------- the XML mirror

    @Test
    fun `the xml dimensions are the ones the layout policy uses`() {
        assertThat(dimens["info_bar_padding_horizontal"]).isEqualTo("${InfoBarLayout.BAR_H_PADDING_DP}dp")
        assertThat(dimens["info_logo_size"]).isEqualTo("${InfoBarLayout.LOGO_SIZE_DP}dp")
        assertThat(dimens["info_text_margin_start"]).isEqualTo("${InfoBarLayout.TEXT_MARGIN_START_DP}dp")
        assertThat(dimens["info_controls_margin_top"]).isEqualTo("${InfoBarLayout.CONTROLS_MARGIN_TOP_DP}dp")
        assertThat(dimens["info_control_min_width"]).isEqualTo("${InfoBarLayout.CONTROL_MIN_WIDTH_DP}dp")
        assertThat(dimens["info_control_margin_start"]).isEqualTo("${InfoBarLayout.CONTROL_MARGIN_START_DP}dp")
        assertThat(dimens["info_control_padding_horizontal"]).isEqualTo("${InfoBarLayout.CONTROL_H_PADDING_DP}dp")
        assertThat(dimens["info_control_text_size"]).isEqualTo("${InfoBarLayout.CONTROL_TEXT_SP}sp")
    }

    private fun parseDimens(file: File): Map<String, String> {
        assertThat(file.exists()).isTrue()
        val pattern = Regex("<dimen\\s+name=\"([^\"]+)\"\\s*>([^<]+)</dimen>")
        return pattern.findAll(file.readText()).associate { match ->
            match.groupValues[1] to match.groupValues[2].trim()
        }
    }

    private companion object {
        /** `activity_player.xml`'s `info_name` / `info_now_next` text sizes. */
        const val NAME_SP = 30
        const val NOW_NEXT_SP = 18
    }
}
