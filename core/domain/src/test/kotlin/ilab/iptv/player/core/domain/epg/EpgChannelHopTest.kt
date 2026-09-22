package ilab.iptv.player.core.domain.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Programme
import org.junit.Test

/**
 * BUG-20260922-018 at the unit level: the hop from `programme.epg_channel_id` back to the business
 * channel id must be **many-to-many**, because one guide channel can serve several business channels.
 *
 * The one case that matters is [shared guide id]: `CCTV1` and `CCTV1 高清` are both bound to
 * `CCTV-1.hk`, and both rows must render that guide. The old one-to-one `associate` kept only the
 * second one, which is why the first row rendered "no EPG" while the diagnostics panel — which asks
 * per channel and never collapses anything — reported the same guide's programmes for it.
 */
class EpgChannelHopTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `channels sharing one guide id all get that guide's programmes`() {
        val sd = channel(id = 1, name = "CCTV1", epgChannelId = "CCTV-1.hk")
        val hd = channel(id = 2, name = "CCTV1 高清", epgChannelId = "CCTV-1.hk")
        val other = channel(id = 3, name = "CCTV2", epgChannelId = "CCTV-2.hk")
        val guide = listOf(
            programme(id = 10, epgChannelId = "CCTV-1.hk", startMs = now),
            programme(id = 11, epgChannelId = "CCTV-1.hk", startMs = now + HOUR),
            programme(id = 12, epgChannelId = "CCTV-2.hk", startMs = now),
        )

        val hopped = EpgChannelHop.programmesByChannel(
            pageChannelIds = listOf(1L, 2L, 3L),
            channels = listOf(sd, hd, other),
            programmes = guide,
        )

        assertThat(hopped[1]?.map { it.id }).containsExactly(10L, 11L)
        assertThat(hopped[2]?.map { it.id }).containsExactly(10L, 11L)
        assertThat(hopped[3]?.map { it.id }).containsExactly(12L)
    }

    /**
     * The page is the ceiling, exactly like the one-to-one version it replaces: a channel bound to the
     * same guide id but sitting on another page is not this page's row, so it is not in the answer.
     */
    @Test
    fun `only the channels the page asked for are mapped`() {
        val onPage = channel(id = 1, name = "CCTV1", epgChannelId = "CCTV-1.hk")
        val offPage = channel(id = 99, name = "CCTV1 高清", epgChannelId = "CCTV-1.hk")
        val guide = listOf(programme(id = 10, epgChannelId = "CCTV-1.hk", startMs = now))

        val hopped = EpgChannelHop.programmesByChannel(
            pageChannelIds = listOf(1L),
            channels = listOf(onPage, offPage),
            programmes = guide,
        )

        assertThat(hopped.keys).containsExactly(1L)
    }

    /** A channel with no binding, or a binding the guide does not mention, is simply absent. */
    @Test
    fun `unbound and unmatched channels are absent from the map`() {
        val unbound = channel(id = 1, name = "没有 EPG", epgChannelId = null)
        val blankId = channel(id = 2, name = "空 id", epgChannelId = "")
        val foreign = channel(id = 3, name = "其它 guide", epgChannelId = "other.hk")
        val guide = listOf(programme(id = 10, epgChannelId = "CCTV-1.hk", startMs = now))

        val hopped = EpgChannelHop.programmesByChannel(
            pageChannelIds = listOf(1L, 2L, 3L),
            channels = listOf(unbound, blankId, foreign),
            programmes = guide,
        )

        assertThat(hopped).isEmpty()
    }

    @Test
    fun `an empty page or an empty guide makes an empty map`() {
        val channel = channel(id = 1, name = "CCTV1", epgChannelId = "CCTV-1.hk")
        assertThat(
            EpgChannelHop.programmesByChannel(emptyList(), listOf(channel), listOf(programme(10, "CCTV-1.hk", now))),
        ).isEmpty()
        assertThat(EpgChannelHop.programmesByChannel(listOf(1L), listOf(channel), emptyList())).isEmpty()
        assertThat(EpgChannelHop.programmesByChannel(listOf(1L), emptyList(), emptyList())).isEmpty()
    }

    /** Programmes of a guide id nobody on the page is bound to are dropped, not crash the hop. */
    @Test
    fun `programmes nobody on the page is bound to are dropped`() {
        val channel = channel(id = 1, name = "CCTV1", epgChannelId = "CCTV-1.hk")
        val hopped = EpgChannelHop.programmesByChannel(
            pageChannelIds = listOf(1L),
            channels = listOf(channel),
            programmes = listOf(
                programme(id = 10, epgChannelId = "CCTV-1.hk", startMs = now),
                programme(id = 11, epgChannelId = "some.other.channel", startMs = now),
            ),
        )
        assertThat(hopped[1]?.map { it.id }).containsExactly(10L)
    }

    private fun channel(id: Long, name: String, epgChannelId: String?): Channel = Channel(
        id = id,
        name = name,
        nameKey = name.lowercase(),
        tvgId = null,
        group = ChannelGroup.CCTV,
        groupKey = "央视",
        logoUrl = null,
        channelNo = null,
        favorite = false,
        hidden = false,
        sortOrder = 0,
        epgChannelId = epgChannelId,
        epgMatch = if (epgChannelId == null) EpgMatchType.NONE else EpgMatchType.NAME_EXACT,
        streamCount = 1,
    )

    private fun programme(id: Long, epgChannelId: String, startMs: Long): Programme = Programme(
        id = id,
        epgChannelId = epgChannelId,
        startMs = startMs,
        stopMs = startMs + HOUR,
        title = "P$id",
        desc = null,
        category = null,
    )

    private companion object {
        const val HOUR = 60L * 60L * 1000L
    }
}
