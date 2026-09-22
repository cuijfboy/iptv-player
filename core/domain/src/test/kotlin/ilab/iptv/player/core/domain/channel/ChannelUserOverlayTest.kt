package ilab.iptv.player.core.domain.channel

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * P3-4's two user overlays, and the rule that keeps them from re-identifying a channel.
 *
 * The point of the split is that `name_key` / `group_key` stay **source-owned** (they are the refresh's
 * upsert key and the EPG matcher's lookup key) while `display_name` / `user_group_title` are the
 * user's. These tests pin the derived "effective" values every screen renders.
 */
class ChannelUserOverlayTest {

    @Test
    fun `without an overlay the effective values are the source's`() {
        val channel = channel(1, name = "CCTV1", groupKey = "cctv", groupTitle = "央视")

        assertThat(channel.shownName).isEqualTo("CCTV1")
        assertThat(ChannelGrouping.effectiveGroupKey(channel)).isEqualTo("cctv")
        assertThat(ChannelGrouping.effectiveGroupTitle(channel)).isEqualTo("央视")
        assertThat(ChannelGrouping.effectiveGroup(channel)).isEqualTo(ChannelGroup.CCTV)
    }

    @Test
    fun `a rename changes only the shown name, never the identity columns`() {
        val channel = channel(1, name = "CCTV1", nameKey = "cctv1", groupKey = "cctv", groupTitle = "央视")
            .copy(displayName = "中央一套")

        assertThat(channel.shownName).isEqualTo("中央一套")
        // The identity the refresh upserts on and the EPG matcher compares on is untouched.
        assertThat(channel.name).isEqualTo("CCTV1")
        assertThat(channel.nameKey).isEqualTo("cctv1")
    }

    @Test
    fun `a group move derives the target key from the user title`() {
        val channel = channel(1, name = "CCTV1", groupKey = "cctv", groupTitle = "央视")
            .copy(userGroupTitle = "卫视")

        assertThat(ChannelGrouping.effectiveGroupTitle(channel)).isEqualTo("卫视")
        // `groupKey("卫视")` is "卫视" (the normalizer only folds width/space/case), and the source
        // `group_key` stays "cctv" so the unique index keeps the row.
        assertThat(ChannelGrouping.effectiveGroupKey(channel)).isEqualTo("卫视")
        assertThat(channel.groupKey).isEqualTo("cctv")
        assertThat(ChannelGrouping.effectiveGroup(channel)).isEqualTo(ChannelGroup.SATELLITE)
    }

    @Test
    fun `sections follow the effective group, so a moved channel renders in its target section`() {
        val moved = channel(1, name = "CCTV1", groupKey = "cctv", groupTitle = "央视")
            .copy(userGroupTitle = "卫视")
        val stayer = channel(2, name = "CCTV2", groupKey = "cctv", groupTitle = "央视")

        val sections = ChannelGrouping.sections(listOf(moved, stayer))

        val byKey = sections.associateBy { it.key }
        assertThat(byKey.keys).containsExactly("cctv", "卫视")
        assertThat(byKey.getValue("cctv").channels.map { it.id }).containsExactly(2L)
        assertThat(byKey.getValue("卫视").channels.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `the sorter ranks a moved channel by its target classification`() {
        val moved = channel(1, name = "CCTV1", groupKey = "cctv", groupTitle = "央视")
            .copy(userGroupTitle = "卫视")
        val other = channel(2, name = "CCTV2", groupKey = "cctv", groupTitle = "央视")

        val sorted = ChannelSorter.sort(listOf(moved, other))

        // 央视 ranks before 卫视, so the channel that stayed in 央视 comes first even though the moved
        // one has the lower id.
        assertThat(sorted.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    private fun channel(
        id: Long,
        name: String,
        nameKey: String = name.lowercase(),
        groupKey: String,
        groupTitle: String?,
    ): Channel = Channel(
        id = id,
        name = name,
        nameKey = nameKey,
        tvgId = null,
        group = ChannelGrouping.classify(groupTitle ?: groupKey),
        groupKey = groupKey,
        groupTitle = groupTitle,
        logoUrl = null,
        channelNo = null,
        favorite = false,
        hidden = false,
        sortOrder = 0,
        epgChannelId = null,
        epgMatch = EpgMatchType.NONE,
        streamCount = 1,
    )
}
