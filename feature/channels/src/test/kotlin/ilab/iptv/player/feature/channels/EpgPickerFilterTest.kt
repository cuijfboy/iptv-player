package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EpgChannelRef
import org.junit.Test

/**
 * The manual-binding picker's search (P3-4), including the honest-empty case the three HK/MO/TW gaps
 * produce.
 *
 * The candidate list below is a stand-in for what the four shipped guides publish: the 中天 family and
 * `TVB Plus` exist, but `TVB星河频道` / `澳视澳门` / `中天新闻` do not (the finding recorded in
 * `docs/05-过程记录/39-EPG繁简折叠.md`: folding does not help, the source simply has no such channel).
 * So a search for any of the three must come back empty — which is the state the dialog turns into
 * "该 guide 无此频道".
 */
class EpgPickerFilterTest {

    private val guide = listOf(
        EpgChannelRef("hk.tvbplus", "TVB Plus"),
        EpgChannelRef("tw.cti.asia", "中天亚洲台"),
        EpgChannelRef("tw.cti.general", "中天综合台"),
        EpgChannelRef("cn.cctv1", "CCTV-1 综合"),
    )

    @Test
    fun `a blank query shows the whole catalogue`() {
        assertThat(EpgPickerFilter.match(guide, "")).hasSize(4)
        assertThat(EpgPickerFilter.match(guide, null)).hasSize(4)
    }

    @Test
    fun `search matches the guide display name case-insensitively`() {
        val hits = EpgPickerFilter.match(guide, "cctv-1")
        assertThat(hits.map { it.id }).containsExactly("cn.cctv1")
    }

    @Test
    fun `search also matches the guide id`() {
        assertThat(EpgPickerFilter.match(guide, "tvbplus").map { it.id }).containsExactly("hk.tvbplus")
    }

    @Test
    fun `the three documented gaps match nothing in the guide`() {
        listOf("TVB星河频道", "澳视澳门", "中天新闻").forEach { gap ->
            assertThat(EpgPickerFilter.match(guide, gap)).isEmpty()
        }
    }

    @Test
    fun `a neighbouring name does match, so the empty result is about the name not the family`() {
        assertThat(EpgPickerFilter.match(guide, "中天").map { it.id })
            .containsExactly("tw.cti.asia", "tw.cti.general")
    }
}
