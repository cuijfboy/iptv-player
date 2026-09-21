package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgChannelIndex
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * The three tiers of docs/02 §6.3 that P2-7 owns: `tvg-id` exact → normalized name → alias table, plus
 * the miss and the manual binding that must survive all three. Each test also checks the *explanation*
 * (`type` + `matchedOn`), because that is what `EPG_MATCH_HIT` logs and what makes a wrong match
 * debuggable without re-running the chain.
 */
class EpgMatcherTest {

    private val matcher = EpgMatcher(aliases = EpgAliases.BUILT_IN)

    private val index = EpgChannelIndex(
        byId = mapOf("CCTV1.cn" to "CCTV1.cn", "CCTV2.cn" to "CCTV2.cn", "hunan.cn" to "hunan.cn"),
        byNameKey = mapOf(
            "cctv-1" to "CCTV1.cn",
            "cctv-1综合" to "CCTV1.cn",
            "cctv-2财经" to "CCTV2.cn",
            "湖南卫视" to "hunan.cn",
        ),
    )

    @Test
    fun `tier 1 matches on the tvg-id and says so`() {
        val report = matcher.match(
            listOf(channel(id = 1, name = "CCTV-1 综合", tvgId = "CCTV1.cn")),
            index,
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.TVG_ID)
        assertThat(hit.epgChannelId).isEqualTo("CCTV1.cn")
        assertThat(hit.matchedOn).isEqualTo("CCTV1.cn")
        assertThat(report.misses).isEmpty()
    }

    @Test
    fun `tier 2 matches on the normalized name when the tvg-id is absent or unknown`() {
        val report = matcher.match(
            listOf(
                channel(id = 1, name = "CCTV-1 综合"),
                // A wrong tvg-id must not stop tier 2: the guide simply has no such id.
                channel(id = 2, name = "CCTV-2 财经", tvgId = "stale.id"),
                // Full-width and spacing differences fold to the same key.
                channel(id = 3, name = "ＣＣＴＶ－1　综合"),
            ),
            index,
        )
        assertThat(report.hits.map { it.channelId to it.type }).containsExactly(
            1L to EpgMatchType.NAME_EXACT,
            2L to EpgMatchType.NAME_EXACT,
            3L to EpgMatchType.NAME_EXACT,
        )
        assertThat(report.hits.first { it.channelId == 2L }.matchedOn).isEqualTo("cctv-2财经")
        assertThat(report.misses).isEmpty()
    }

    @Test
    fun `tier 3 matches through the alias table and reports the alias key`() {
        // `CCTV-5 体育` is not a display name in the guide; the alias maps it to the id `CCTV5`.
        val report = matcher.match(
            listOf(channel(id = 7, name = "CCTV-5 体育")),
            index.copy(byId = index.byId + ("CCTV5" to "CCTV5")),
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.ALIAS)
        assertThat(hit.epgChannelId).isEqualTo("CCTV5")
        assertThat(hit.matchedOn).isEqualTo("cctv-5体育")
    }

    @Test
    fun `an alias whose target is a display name resolves through the name index`() {
        val report = matcher.match(
            listOf(channel(id = 9, name = "湖南卫视-高清", group = ChannelGroup.SATELLITE)),
            index,
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.ALIAS)
        assertThat(hit.epgChannelId).isEqualTo("hunan.cn")
    }

    @Test
    fun `a channel no tier can bind is a miss carrying what was tried`() {
        val report = matcher.match(
            listOf(channel(id = 42, name = "某个不存在的台", tvgId = "nope.id")),
            index,
        )
        assertThat(report.hits).isEmpty()
        val miss = report.misses.single()
        assertThat(miss.channelId).isEqualTo(42)
        assertThat(miss.nameKey).isEqualTo("某个不存在的台")
        assertThat(miss.triedTvgId).isEqualTo("nope.id")
        assertThat(report.attempted).isEqualTo(1)
    }

    @Test
    fun `a manual binding is preserved and never re-matched`() {
        val report = matcher.match(
            listOf(
                channel(
                    id = 5,
                    name = "CCTV-1 综合",
                    tvgId = "CCTV1.cn",
                    epgChannelId = "user.picked.id",
                    epgMatch = EpgMatchType.MANUAL,
                ),
            ),
            index,
        )
        assertThat(report.hits).isEmpty()
        assertThat(report.misses).isEmpty()
        assertThat(report.preserved).containsExactly(5L)
    }

    @Test
    fun `a stale non-manual binding is re-resolved`() {
        val report = matcher.match(
            listOf(
                channel(
                    id = 5,
                    name = "CCTV-1 综合",
                    tvgId = "CCTV1.cn",
                    epgChannelId = "old.id",
                    epgMatch = EpgMatchType.TVG_ID,
                ),
            ),
            index,
        )
        assertThat(report.hits.single().epgChannelId).isEqualTo("CCTV1.cn")
    }

    @Test
    fun `an empty guide matches nothing and never claims a hit`() {
        val report = matcher.match(
            listOf(channel(id = 1, name = "CCTV-1 综合", tvgId = "CCTV1.cn")),
            EpgChannelIndex(),
        )
        assertThat(report.hits).isEmpty()
        assertThat(report.misses).hasSize(1)
    }

    @Test
    fun `the alias table normalizes its keys and keeps the built-in seed small`() {
        val aliases = EpgAliases(mapOf("CCTV-1 综合" to "CCTV1"))
        assertThat(aliases.targetFor("ＣＣＴＶ－1　综合")).isEqualTo("CCTV1")
        assertThat(aliases.targetFor("cctv-1综合")).isEqualTo("CCTV1")
        assertThat(aliases.targetFor("something else")).isNull()
        // P3-5 owns the large table; the seed exists to make the tier real and testable.
        assertThat(EpgAliases.BUILT_IN.size).isAtMost(20)
    }

    @Test
    fun `coverage counts matched channels per group`() {
        val channels = listOf(
            channel(id = 1, name = "CCTV-1", group = ChannelGroup.CCTV),
            channel(id = 2, name = "CCTV-2", group = ChannelGroup.CCTV),
            channel(id = 3, name = "湖南卫视", group = ChannelGroup.SATELLITE),
            channel(id = 4, name = "本地台", group = ChannelGroup.LOCAL),
        )
        val coverage = EpgCoverageCalculator.of(channels, matchedChannelIds = setOf(1, 3))
        assertThat(coverage.matched).isEqualTo(2)
        assertThat(coverage.total).isEqualTo(4)
        assertThat(coverage.byGroup).containsExactly(
            ChannelGroup.CCTV, 1,
            ChannelGroup.SATELLITE, 1,
        )
        assertThat(coverage.ratio).isEqualTo(0.5)
    }

    @Test
    fun `a guide with no channels reports zero coverage without dividing by zero`() {
        val coverage = EpgCoverageCalculator.of(emptyList(), emptySet())
        assertThat(coverage.ratio).isEqualTo(0.0)
    }
}
