package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P3-7 item 1/5: the search screen's BACK rule ("搜索页 · 有输入 → 清空输入；否则 → 回浏览页"), which
 * used to be an inline branch inside `SearchActivity.onKeyDown`.
 */
class SearchBackPolicyTest {

    @Test
    fun `a typed query is the first thing back removes`() {
        assertThat(SearchBackPolicy.decide("hnws")).isEqualTo(SearchBackAction.CLEAR_QUERY)
    }

    @Test
    fun `an empty query leaves the screen`() {
        assertThat(SearchBackPolicy.decide("")).isEqualTo(SearchBackAction.LEAVE_SCREEN)
    }

    @Test
    fun `a single digit is already a level`() {
        // The number keys are the fast path of P3-2, so one digit must be clearable with BACK.
        assertThat(SearchBackPolicy.decide("1")).isEqualTo(SearchBackAction.CLEAR_QUERY)
    }

    @Test
    fun `the sequence from a typed query to the browse screen is two presses`() {
        val first = SearchBackPolicy.decide("cctv")
        val second = SearchBackPolicy.decide("")

        assertThat(listOf(first, second))
            .containsExactly(SearchBackAction.CLEAR_QUERY, SearchBackAction.LEAVE_SCREEN)
            .inOrder()
    }
}
