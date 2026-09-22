package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/04 P3-2: 搜索索引与匹配 — channels by name/number, programmes by title, both by 拼音首字母. */
class SearchIndexTest {

    private val now = 1_700_000_000_000L

    private val cctv1 = SearchChannel(channelId = 1L, name = "CCTV1 综合", number = 1, groupKey = "cctv")
    private val cctv13 = SearchChannel(channelId = 13L, name = "CCTV13 新闻", number = 13, groupKey = "cctv")
    private val hunan = SearchChannel(channelId = 30L, name = "湖南卫视", number = 30, groupKey = "sat")

    private val index = SearchIndex.build(
        channels = listOf(cctv1, cctv13, hunan),
        programmes = listOf(
            SearchProgramme(
                channelId = 13L,
                channelName = "CCTV13 新闻",
                title = "新闻联播",
                startMs = now - 60_000,
                stopMs = now + 60_000,
            ),
            SearchProgramme(
                channelId = 1L,
                channelName = "CCTV1 综合",
                title = "新闻联播",
                startMs = now + 6 * 60 * 60 * 1000,
                stopMs = now + 7 * 60 * 60 * 1000,
            ),
            SearchProgramme(
                channelId = 30L,
                channelName = "湖南卫视",
                title = "歌手",
                startMs = now + 60_000,
                stopMs = now + 120_000,
            ),
        ),
    )

    @Test
    fun `an exact channel number wins`() {
        val hits = index.search("1", now)
        assertThat(hits.first()).isInstanceOf(ChannelHit::class.java)
        assertThat((hits.first() as ChannelHit).channelId).isEqualTo(1L)
    }

    @Test
    fun `a channel name prefix beats a longer name that merely contains it`() {
        val hits = index.search("cctv1", now)
        val channels = hits.filterIsInstance<ChannelHit>()
        // "CCTV1 综合" is a prefix match; "CCTV13 新闻" only contains "cctv1".
        assertThat(channels.first().channelId).isEqualTo(1L)
        assertThat(channels.map { it.channelId }).contains(13L)
    }

    @Test
    fun `chinese substrings and pinyin initials both find the channel`() {
        val substring = index.search("新闻", now).filterIsInstance<ChannelHit>()
        assertThat(substring.map { it.channelId }).contains(13L)

        val initials = index.search("hnws", now).filterIsInstance<ChannelHit>()
        assertThat(initials.map { it.channelId }).containsExactly(30L)
    }

    @Test
    fun `a programme hit keeps its channel and prefers the one on air`() {
        val hits = index.search("新闻联播", now).filterIsInstance<ProgrammeHit>()
        assertThat(hits.map { it.channelId }).containsExactly(13L, 1L)
        assertThat(hits.first().channelId).isEqualTo(13L)
        assertThat(hits.first().live).isTrue()
        assertThat(hits.last().live).isFalse()
    }

    @Test
    fun `programmes are deduplicated per channel and title`() {
        val repeated = SearchIndex.build(
            channels = listOf(cctv1),
            programmes = listOf(
                SearchProgramme(1L, "CCTV1 综合", "新闻联播", now, now + 1),
                SearchProgramme(1L, "CCTV1 综合", "新闻联播", now + 2, now + 3),
            ),
        )
        assertThat(repeated.search("新闻联播", now).filterIsInstance<ProgrammeHit>()).hasSize(1)
    }

    @Test
    fun `an empty or unmatched query returns nothing`() {
        assertThat(index.search("", now)).isEmpty()
        assertThat(index.search("   ", now)).isEmpty()
        assertThat(index.search("zzzz", now)).isEmpty()
    }

    @Test
    fun `the limit caps the result list`() {
        val many = (1..120).map { SearchChannel(it.toLong(), "测试频道$it", it, "local") }
        val big = SearchIndex.build(many, emptyList())
        assertThat(big.search("测试", now, limit = 10)).hasSize(10)
        assertThat(big.search("测试", now)).hasSize(SearchIndex.DEFAULT_LIMIT)
    }

    @Test
    fun `counts describe what was indexed`() {
        assertThat(index.channelCount).isEqualTo(3)
        assertThat(index.programmeCount).isEqualTo(3)
        assertThat(index.keyCount).isGreaterThan(3)
    }
}
