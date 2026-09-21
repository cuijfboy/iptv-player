package ilab.iptv.player.core.domain.channel

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/** Pins the grouping policy: key derivation, the `"other"` fallback and list sections. */
class ChannelGroupingTest {

    @Test
    fun `group key is the normalized title, not the enum`() {
        assertThat(ChannelGrouping.groupKey("地方/其他")).isEqualTo("地方/其他")
        assertThat(ChannelGrouping.groupKey("  央 视 ")).isEqualTo("央_视")
        assertThat(ChannelGrouping.groupKey("ＣＣＴＶ")).isEqualTo("cctv")
    }

    @Test
    fun `a missing or blank group title falls back to other`() {
        assertThat(ChannelGrouping.groupKey(null)).isEqualTo(ChannelGroup.OTHER.key)
        assertThat(ChannelGrouping.groupKey("   ")).isEqualTo(ChannelGroup.OTHER.key)
        assertThat(ChannelGrouping.classify(null)).isEqualTo(ChannelGroup.OTHER)
    }

    @Test
    fun `classification reads the title keywords in order`() {
        assertThat(ChannelGrouping.classify("央视")).isEqualTo(ChannelGroup.CCTV)
        assertThat(ChannelGrouping.classify("卫视")).isEqualTo(ChannelGroup.SATELLITE)
        assertThat(ChannelGrouping.classify("港澳台")).isEqualTo(ChannelGroup.HK_MO_TW)
        assertThat(ChannelGrouping.classify("地方/其他")).isEqualTo(ChannelGroup.LOCAL)
        assertThat(ChannelGrouping.classify("其他频道")).isEqualTo(ChannelGroup.OTHER)
        assertThat(ChannelGrouping.classify("电影")).isEqualTo(ChannelGroup.OTHER)
    }

    @Test
    fun `same-named channels in different groups stay in different sections`() {
        val sections = ChannelGrouping.sections(
            listOf(
                channel(1, "CCTV1", groupTitle = "央视"),
                channel(2, "CCTV1", groupTitle = "地方/其他"),
            ),
        )

        assertThat(sections).hasSize(2)
        assertThat(sections.map { it.key }).containsExactly("央视", "地方/其他").inOrder()
    }

    @Test
    fun `sections follow the classification display order`() {
        val sections = ChannelGrouping.sections(
            listOf(
                channel(1, "A", groupTitle = "地方/其他"),
                channel(2, "B", groupTitle = "卫视"),
                channel(3, "C", groupTitle = "央视"),
            ),
        )

        assertThat(sections.map { it.group })
            .containsExactly(ChannelGroup.CCTV, ChannelGroup.SATELLITE, ChannelGroup.LOCAL)
            .inOrder()
    }

    companion object {
        fun channel(
            id: Long,
            name: String,
            groupTitle: String? = null,
            group: ChannelGroup = ChannelGrouping.classify(groupTitle),
            channelNo: Int? = null,
        ) = Channel(
            id = id,
            name = name,
            tvgId = null,
            group = group,
            logoUrl = null,
            channelNo = channelNo,
            favorite = false,
            hidden = false,
            sortOrder = 0,
            epgChannelId = null,
            epgMatch = EpgMatchType.NONE,
            streamCount = 1,
            nameKey = ChannelGrouping.normalizeKey(name),
            groupKey = ChannelGrouping.groupKey(groupTitle),
            groupTitle = groupTitle,
        )
    }
}
