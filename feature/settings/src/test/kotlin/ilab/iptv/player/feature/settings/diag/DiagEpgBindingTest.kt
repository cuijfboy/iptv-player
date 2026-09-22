package ilab.iptv.player.feature.settings.diag

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EpgBindingReport
import ilab.iptv.player.core.model.EpgBindingRow
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.EpgTimeWindow
import org.junit.Test

/**
 * BUG-20260922-018's evidence block: the five fields the card names — channelId / epgChannelId /
 * programmesInWindow / window(from,to) / matchedBy — have to be readable off the panel without a
 * legend, and the two blank-row reasons have to stay distinguishable.
 */
class DiagEpgBindingTest {

    private val window = EpgTimeWindow(fromMs = 1_790_049_600_000L, toMs = 1_790_071_200_000L)

    private fun report(vararg rows: EpgBindingRow) = EpgBindingReport(window = window, rows = rows.toList())

    private fun block(vararg rows: EpgBindingRow) =
        DiagEpgBinding.block(report(*rows), windowLabel = "09-22 12:00 – 09-22 18:00", readAt = "09-22 12:30")
            .facts
            .associate { it.label to it.value }

    @Test
    fun `the window, the read time and the same-instant coverage totals are on the block`() {
        val facts = block(
            EpgBindingRow(41, "CGTN俄语", 41, "561392", EpgMatchType.NAME_FUZZY, 4),
            EpgBindingRow(2, "CCTV-11戏曲", 2, "545943", EpgMatchType.NAME_EXACT, 6),
            EpgBindingRow(777, "CCTV-12社会与法", 777, "545944", EpgMatchType.NAME_EXACT, 0),
            EpgBindingRow(9, "没有绑定", null, null, EpgMatchType.NONE, 0),
        )

        assertThat(facts["窗口"]).isEqualTo("09-22 12:00 – 09-22 18:00")
        assertThat(facts["读取时刻"]).isEqualTo("09-22 12:30")
        assertThat(facts["覆盖"]).isEqualTo("匹配 3 / 可看 2 / 空绑 1 / 共 4")
        assertThat(facts).containsKey("口径")
    }

    @Test
    fun `a blank row says whether nothing is bound or the binding is empty in the window`() {
        val facts = block(
            EpgBindingRow(777, "CCTV-12社会与法", 777, "545944", EpgMatchType.NAME_EXACT, 0),
            EpgBindingRow(9, "没有绑定", null, null, EpgMatchType.NONE, 0),
        )

        // Bound to 545944, 0 programmes in these six hours: the guide id is empty, not missing.
        assertThat(facts["#777 [777] CCTV-12社会与法"]).isEqualTo("id=545944 窗口内 0 条 依据=NAME_EXACT")
        // No guide id at all: a different reason the row is blank.
        assertThat(facts["#9 没有绑定"]).isEqualTo("id=(未绑定) 窗口内 0 条 依据=NONE")
    }

    @Test
    fun `the line carries the channel number and the match tier a reader needs to act on it`() {
        val facts = block(
            EpgBindingRow(2, "CCTV-11戏曲", 2, "545943", EpgMatchType.NAME_EXACT, 6),
            // The QA run had CCTV1 hidden, which is why it could not be observed in the grid.
            EpgBindingRow(1, "CCTV1", 1, "CCTV-1.hk", EpgMatchType.NAME_EXACT, 10, hidden = true),
        )

        assertThat(facts.keys).containsAtLeast(
            "#2 [2] CCTV-11戏曲",
            "#1 [1] CCTV1（已隐藏）",
        )
        assertThat(facts["#1 [1] CCTV1（已隐藏）"]).isEqualTo("id=CCTV-1.hk 窗口内 10 条 依据=NAME_EXACT")
    }
}
