package ilab.iptv.player.core.domain.selection

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.playback.stream
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.SelectionInput
import ilab.iptv.player.core.model.Stream
import org.junit.Test

class DefaultStreamSelectorTest {

    private val selector = DefaultStreamSelector()

    @Test
    fun `score wins, then the frozen tie breakers`() {
        val lowScore = stream(1, score = 40)
        val highScore = stream(2, score = 90)
        val ranked = selector.rank(input(lowScore, highScore))
        assertThat(ranked.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `equal scores fall back to priority`() {
        val ranked = selector.rank(input(stream(1, score = 80, priority = 3), stream(2, score = 80, priority = 1)))
        assertThat(ranked.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `a recently working stream outranks an unknown one and the id settles the rest`() {
        val ranked = selector.rank(
            input(
                stream(1, score = 80),
                stream(2, score = 80, lastOkAtMs = 5_000),
                stream(3, score = 80, lastOkAtMs = 9_000),
            ),
        )
        assertThat(ranked.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun `ranking never drops a candidate`() {
        val ranked = selector.rank(input(stream(1, score = 0), stream(2, score = 0)))
        assertThat(ranked).hasSize(2)
    }

    private fun input(vararg streams: Stream): SelectionInput = SelectionInput(
        channel = Channel(
            id = 1,
            name = "CCTV-1",
            tvgId = null,
            group = ChannelGroup.CCTV,
            logoUrl = null,
            channelNo = 1,
            favorite = false,
            hidden = false,
            sortOrder = 0,
            epgChannelId = null,
            epgMatch = EpgMatchType.NONE,
            streamCount = streams.size,
        ),
        candidates = streams.toList(),
        nowMs = 10_000,
        engineCaps = emptySet(),
        device = ilab.iptv.player.core.domain.scoring.DEVICE_1080P_NO_PASSTHROUGH,
        health = emptyMap(),
    )
}
