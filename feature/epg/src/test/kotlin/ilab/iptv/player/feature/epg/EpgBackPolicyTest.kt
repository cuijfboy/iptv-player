package ilab.iptv.player.feature.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P3-7 item 1/5: the EPG grid's BACK rule ("EPG 网格 · 详情弹层 → 关弹层；否则 → 回浏览页").
 */
class EpgBackPolicyTest {

    @Test
    fun `back with the detail layer up closes the detail`() {
        assertThat(EpgBackPolicy.decide(detailOpen = true)).isEqualTo(EpgBackAction.CLOSE_DETAIL)
    }

    @Test
    fun `back with nothing open returns to the browse screen`() {
        assertThat(EpgBackPolicy.decide(detailOpen = false)).isEqualTo(EpgBackAction.LEAVE_SCREEN)
    }
}
