package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgChannelIndex
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * The tiers of docs/02 §6.3 that P2-7 + P3-5 own: `tvg-id` exact → normalized name → feed/punctuation
 * folded name → alias table, plus the miss and the manual binding that must survive all of them. Each
 * test also checks the *explanation* (`type` + `matchedOn`, and the `guideKey` of a folded hit),
 * because that is what `EPG_MATCH_HIT` logs and what makes a wrong match debuggable without re-running
 * the chain.
 */
class EpgMatcherTest {

    private val matcher = EpgMatcher(aliases = EpgAliases.BUILT_IN)

    private val index = EpgChannelIndex(
        byId = mapOf("CCTV1.cn" to "CCTV1.cn", "CCTV2.cn" to "CCTV2.cn", "hunan.cn" to "hunan.cn"),
        byNameKey = mapOf(
            "cctv-1" to listOf("CCTV1.cn"),
            "cctv-1综合" to listOf("CCTV1.cn"),
            "cctv-2财经" to listOf("CCTV2.cn"),
            "湖南卫视" to listOf("hunan.cn"),
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
    fun `a feed marker is folded before the alias table is consulted`() {
        // `湖南卫视-高清` used to need an alias entry; P3-5's tier 3 folds it to `湖南卫视` first, and
        // the hit still says exactly which fold was used (`matchedOn` = the variant, `guideKey` = the
        // guide name it landed on).
        val report = matcher.match(
            listOf(channel(id = 9, name = "湖南卫视-高清", group = ChannelGroup.SATELLITE)),
            index,
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.NAME_FUZZY)
        assertThat(hit.epgChannelId).isEqualTo("hunan.cn")
        assertThat(hit.matchedOn).isEqualTo("湖南卫视")
        assertThat(hit.guideKey).isEqualTo("湖南卫视")
    }

    @Test
    fun `an alias whose target is a display name resolves through the name index`() {
        // `央视新闻` is CCTV-13 by knowledge, not by spelling, so it lives in the alias table; its
        // target is a guide *name* (the public guide's ids are per-source numbers).
        val report = matcher.match(
            listOf(channel(id = 9, name = "央视新闻", group = ChannelGroup.CCTV)),
            index.copy(byNameKey = index.byNameKey + ("cctv-13新闻" to listOf("CCTV13.cn"))),
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.ALIAS)
        assertThat(hit.epgChannelId).isEqualTo("CCTV13.cn")
        assertThat(hit.matchedOn).isEqualTo("央视新闻")
        assertThat(hit.guideKey).isEqualTo("cctv-13新闻")
    }

    @Test
    fun `an alias entry also catches the name with a feed marker attached`() {
        // `福建东南卫视 高清` must not lose the `福建东南卫视` entry just because the source added a
        // marker; the alias lookup runs on the tier-3 variant keys too.
        val report = matcher.match(
            listOf(channel(id = 11, name = "福建东南卫视 高清", group = ChannelGroup.SATELLITE)),
            index.copy(
                byNameKey = index.byNameKey + ("东南卫视" to listOf("dndw.cn")),
                byId = index.byId + ("dndw.cn" to "dndw.cn"),
            ),
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.ALIAS)
        assertThat(hit.epgChannelId).isEqualTo("dndw.cn")
        assertThat(hit.matchedOn).isEqualTo("福建东南卫视")
    }

    @Test
    fun `a traditional guide name binds a simplified playlist name with no alias entry`() {
        // The 港澳台 shape of the shipped fixture: the guide spells 澳視澳門, the playlist 澳视澳门.
        // With an empty alias table this is a tier-3 hit, and the explanation names both spellings —
        // `matchedOn` is the folded channel-side key, `guideKey` the guide's own (Traditional) one.
        val traditional = EpgMatcher(aliases = EpgAliases.EMPTY)
        val report = traditional.match(
            listOf(channel(id = 21, name = "澳视澳门", group = ChannelGroup.HK_MO_TW)),
            EpgChannelIndex(byId = emptyMap(), byNameKey = mapOf("澳視澳門" to listOf("mo.id"))),
        )
        val hit = report.hits.single()
        assertThat(hit.type).isEqualTo(EpgMatchType.NAME_FUZZY)
        assertThat(hit.epgChannelId).isEqualTo("mo.id")
        assertThat(hit.matchedOn).isEqualTo("澳视澳门")
        assertThat(hit.guideKey).isEqualTo("澳視澳門")
        assertThat(report.misses).isEmpty()
    }

    @Test
    fun `the script rule answers the cases the 凤凰 alias entries were written for`() {
        // P3-5 bridged these two by table entry because a name comparison cannot cross scripts. The
        // rule now does it with an empty alias table, which is the point of preferring a rule.
        val index = EpgChannelIndex(
            byId = emptyMap(),
            byNameKey = mapOf(
                "鳳凰衛視中文台" to listOf("phoenix.cn"),
                "鳳凰衛視資訊台" to listOf("phoenix.info"),
            ),
        )
        val report = EpgMatcher(aliases = EpgAliases.EMPTY).match(
            listOf(
                channel(id = 31, name = "凤凰卫视中文台", group = ChannelGroup.HK_MO_TW),
                channel(id = 32, name = "凤凰卫视资讯台", group = ChannelGroup.HK_MO_TW),
            ),
            index,
        )
        assertThat(report.hits.map { it.channelId to it.epgChannelId }).containsExactly(
            31L to "phoenix.cn",
            32L to "phoenix.info",
        )
        assertThat(report.hits.map { it.type }).containsExactly(
            EpgMatchType.NAME_FUZZY,
            EpgMatchType.NAME_FUZZY,
        )
        // With the shipped table the same channels still resolve, and still through tier 3 — the alias
        // entries are now redundant rather than load-bearing (P3-5's rule 5: a big table means a
        // missing rule).
        val withAliases = matcher.match(
            listOf(channel(id = 31, name = "凤凰卫视中文台", group = ChannelGroup.HK_MO_TW)),
            index,
        ).hits.single()
        assertThat(withAliases.type).isEqualTo(EpgMatchType.NAME_FUZZY)
        assertThat(withAliases.matchedOn).isEqualTo("凤凰卫视中文台")
    }

    @Test
    fun `folding a script never binds a channel to a different one in the same family`() {
        // The counter-example the fold has to survive: a guide that carries 中天綜合台 / 中天娛樂台 /
        // 中天亞洲台 (and, measured on 2026-09-22, no 中天新聞 at all) must not hand 中天新闻 the
        // programmes of a sibling, and the siblings must not swallow each other.
        val guide = EpgChannelIndex(
            byId = emptyMap(),
            byNameKey = mapOf(
                "中天綜合台" to listOf("cti.zh"),
                "中天娛樂台" to listOf("cti.yl"),
                "中天亞洲台" to listOf("cti.yz"),
                "黃金翡翠台" to listOf("tvb.gold"),
                "翡翠台" to listOf("tvb.jade"),
            ),
        )
        val report = matcher.match(
            listOf(
                channel(id = 41, name = "中天综合台", group = ChannelGroup.HK_MO_TW),
                channel(id = 42, name = "中天娱乐台", group = ChannelGroup.HK_MO_TW),
                channel(id = 43, name = "中天新闻", group = ChannelGroup.HK_MO_TW),
                channel(id = 44, name = "翡翠台", group = ChannelGroup.HK_MO_TW),
            ),
            guide,
        )
        assertThat(report.hits.map { it.channelId to it.epgChannelId }).containsExactly(
            41L to "cti.zh",
            42L to "cti.yl",
            44L to "tvb.jade",
        )
        // 中天新闻 is a miss — the guide simply has no such channel, and neither folding nor the alias
        // table may invent one out of its siblings.
        assertThat(report.misses.map { it.channelId }).containsExactly(43L)
    }

    @Test
    fun `a plus channel is not folded into its plain neighbour`() {
        // The safety property of the whole round: `CCTV5+` (a different channel) must never take
        // `CCTV5`'s guide, and `CCTV5` must never take `CCTV5+`'s.
        val index = EpgChannelIndex(
            byId = emptyMap(),
            byNameKey = mapOf("cctv5" to listOf("cctv5.id"), "cctv5plus" to listOf("cctv5plus.id")),
        )
        val report = matcher.match(
            listOf(
                channel(id = 1, name = "CCTV5", group = ChannelGroup.CCTV),
                channel(id = 2, name = "CCTV5+", group = ChannelGroup.CCTV),
                channel(id = 3, name = "CCTV4K", group = ChannelGroup.CCTV),
            ),
            index,
        )
        assertThat(report.hits.single { it.channelId == 1L }.epgChannelId).isEqualTo("cctv5.id")
        assertThat(report.hits.single { it.channelId == 2L }.epgChannelId).isEqualTo("cctv5plus.id")
        // Nothing in the guide is called CCTV4K → it is a miss, not a silent bind to `cctv5`.
        assertThat(report.misses.map { it.channelId }).containsExactly(3L)
    }

    @Test
    fun `a zero-programme stub is offered as a candidate and loses to the column id`() {
        // The EPG-BIND root cause in miniature: the guide declares `CCTV2` (a stub with no
        // programmes) and the real data under `CCTV-2 财经`. The matcher's job is to offer *both* —
        // before this round it `continue`d on the name hit and the column id never reached the
        // decision. Picking by window depth is `EpgBindingPreference`'s job, exercised here so the
        // matcher's output is shown to be sufficient for the fix.
        val guide = EpgChannelIndex(
            byId = emptyMap(),
            byNameKey = mapOf(
                "cctv2" to listOf("561310"),
                "cctv-2财经" to listOf("545933"),
            ),
        )
        val report = matcher.match(
            listOf(channel(id = 1, name = "CCTV2", group = ChannelGroup.CCTV)),
            guide,
        )
        assertThat(report.hits.map { it.epgChannelId }).containsExactly("561310", "545933")
        assertThat(report.hits.map { it.type }).containsExactly(
            EpgMatchType.NAME_EXACT,
            EpgMatchType.NAME_FUZZY,
        )
        assertThat(report.misses).isEmpty()

        val candidates = report.hits.map { hit ->
            EpgBindingCandidate(
                channelId = hit.channelId,
                epgChannelId = hit.epgChannelId,
                type = hit.type,
                matchedOn = hit.matchedOn,
                guideKey = hit.guideKey,
                sourceOrder = 0,
            )
        }
        val chosen = EpgBindingPreference.choose(candidates) { id -> if (id == "545933") 274 else 0 }
        assertThat(chosen!!.epgChannelId).isEqualTo("545933")
    }

    @Test
    fun `a name hit no longer hides the alias tier`() {
        // The short circuit that made "补别名也修不好": the channel matches a guide stub by name, so
        // the alias tier never ran. It runs now, and its target is a second candidate.
        val aliased = EpgMatcher(aliases = EpgAliases(mapOf("CCTV2" to "中央二套财经")))
        val guide = EpgChannelIndex(
            byId = emptyMap(),
            byNameKey = mapOf(
                "cctv2" to listOf("stub.id"),
                "中央二套财经" to listOf("thick.id"),
            ),
        )
        val report = aliased.match(
            listOf(channel(id = 1, name = "CCTV2", group = ChannelGroup.CCTV)),
            guide,
        )
        assertThat(report.hits.map { it.epgChannelId to it.type }).containsExactly(
            "stub.id" to EpgMatchType.NAME_EXACT,
            "thick.id" to EpgMatchType.ALIAS,
        )
        assertThat(report.hits.first { it.epgChannelId == "thick.id" }.matchedOn).isEqualTo("cctv2")
    }

    @Test
    fun `a guide name declared twice offers both ids`() {
        // 52 groups of the shipped guide declare one name under several ids (东方卫视 → three). The
        // index kept only the first; every id is a candidate now, so the depth rule can choose.
        val guide = EpgChannelIndex(
            byId = emptyMap(),
            byNameKey = mapOf("东方卫视" to listOf("thin.cn", "deep.cn", "empty.cn")),
        )
        val report = matcher.match(
            listOf(channel(id = 1, name = "东方卫视", group = ChannelGroup.SATELLITE)),
            guide,
        )
        assertThat(report.hits.map { it.epgChannelId })
            .containsExactly("thin.cn", "deep.cn", "empty.cn").inOrder()
        assertThat(report.hits.map { it.type }.toSet()).containsExactly(EpgMatchType.NAME_EXACT)
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
        // P3-5 filled the table from real misses, so the bound moved up — but it stays a bound: growth
        // is a decision (a missing normalization rule is the usual cause), not a drift.
        assertThat(EpgAliases.BUILT_IN.size).isAtMost(40)
        assertThat(EpgAliases.BUILT_IN.size).isAtLeast(20)
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
        assertThat(coverage.byGroupTotal).containsExactly(
            ChannelGroup.CCTV, 2,
            ChannelGroup.SATELLITE, 1,
            ChannelGroup.LOCAL, 1,
        )
        assertThat(coverage.ratio).isEqualTo(0.5)
    }

    @Test
    fun `a guide with no channels reports zero coverage without dividing by zero`() {
        val coverage = EpgCoverageCalculator.of(emptyList(), emptySet())
        assertThat(coverage.ratio).isEqualTo(0.0)
    }

    @Test
    fun `the mainstream slice excludes the local long tail`() {
        val channels = listOf(
            channel(id = 1, name = "CCTV-1", group = ChannelGroup.CCTV),
            channel(id = 2, name = "CCTV-2", group = ChannelGroup.CCTV),
            channel(id = 3, name = "湖南卫视", group = ChannelGroup.SATELLITE),
            channel(id = 4, name = "凤凰中文", group = ChannelGroup.HK_MO_TW),
            channel(id = 5, name = "河北新闻综合", group = ChannelGroup.LOCAL),
            channel(id = 6, name = "导视资讯", group = ChannelGroup.OTHER),
        )
        val coverage = EpgCoverageCalculator.of(channels, matchedChannelIds = setOf(1, 3, 5, 6))
        val mainstream = EpgCoverageCalculator.mainstream(coverage)
        // 2 of the 4 mainstream channels are covered (CCTV-1, 湖南卫视) — the local hit does not count.
        assertThat(mainstream.matched).isEqualTo(2)
        assertThat(mainstream.total).isEqualTo(4)
        assertThat(mainstream.ratio).isEqualTo(0.5)
        // Overall is higher here precisely because the local tail is small in this fixture.
        assertThat(coverage.ratio).isEqualTo(4.0 / 6.0)
    }

    @Test
    fun `a list with no mainstream channels reports a zero slice instead of dividing by zero`() {
        val channels = listOf(channel(id = 1, name = "本地台", group = ChannelGroup.LOCAL))
        val coverage = EpgCoverageCalculator.of(channels, matchedChannelIds = setOf(1))
        val mainstream = EpgCoverageCalculator.mainstream(coverage)
        assertThat(mainstream.total).isEqualTo(0)
        assertThat(mainstream.ratio).isEqualTo(0.0)
    }

    @Test
    fun `coverage tells an empty binding apart from one with programmes`() {
        val channels = listOf(
            channel(id = 1, name = "CCTV-1", group = ChannelGroup.CCTV),
            // EPG-TRAD-1's 三沙卫视: an id was bound, the guide has no <programme> for it.
            channel(id = 2, name = "三沙卫视", group = ChannelGroup.CCTV),
            channel(id = 3, name = "湖南卫视", group = ChannelGroup.SATELLITE),
            channel(id = 4, name = "凤凰中文", group = ChannelGroup.HK_MO_TW),
            channel(id = 5, name = "本地台", group = ChannelGroup.LOCAL),
        )
        val coverage = EpgCoverageCalculator.of(
            channels,
            matchedChannelIds = setOf(1, 2, 3, 5),
            channelsWithProgrammes = setOf(1, 3, 5),
        )

        assertThat(coverage.matched).isEqualTo(4)
        assertThat(coverage.withProgrammes).isEqualTo(3)
        assertThat(coverage.emptyBinding).isEqualTo(1)
        assertThat(coverage.ratio).isEqualTo(0.8)
        assertThat(coverage.programmedRatio).isEqualTo(0.6)
        assertThat(coverage.byGroupWithProgrammes).containsExactly(
            ChannelGroup.CCTV, 1,
            ChannelGroup.SATELLITE, 1,
            ChannelGroup.LOCAL, 1,
        )
    }

    @Test
    fun `an empty binding is not counted in the mainstream reading, which is what the target gates on`() {
        val channels = listOf(
            channel(id = 1, name = "CCTV-1", group = ChannelGroup.CCTV),
            channel(id = 2, name = "三沙卫视", group = ChannelGroup.CCTV),
            channel(id = 3, name = "湖南卫视", group = ChannelGroup.SATELLITE),
        )
        val coverage = EpgCoverageCalculator.of(
            channels,
            matchedChannelIds = setOf(1, 2, 3),
            channelsWithProgrammes = setOf(1, 3),
        )
        val mainstream = EpgCoverageCalculator.mainstream(coverage)

        // The id-side reading is a perfect 3/3 here; the reading a viewer experiences is 2/3, and the
        // difference is exactly the one binding that would show a blank grid.
        assertThat(mainstream.matched).isEqualTo(3)
        assertThat(mainstream.ratio).isEqualTo(1.0)
        assertThat(mainstream.withProgrammes).isEqualTo(2)
        assertThat(mainstream.emptyBinding).isEqualTo(1)
        assertThat(mainstream.programmedRatio).isEqualTo(2.0 / 3.0)
    }

    @Test
    fun `a list where every binding is empty reports nothing covered rather than everything`() {
        val channels = listOf(channel(id = 1, name = "三沙卫视", group = ChannelGroup.SATELLITE))
        val coverage = EpgCoverageCalculator.of(
            channels,
            matchedChannelIds = setOf(1),
            channelsWithProgrammes = emptySet(),
        )

        assertThat(coverage.matched).isEqualTo(1)
        assertThat(coverage.withProgrammes).isEqualTo(0)
        assertThat(coverage.emptyBinding).isEqualTo(1)
        assertThat(coverage.programmedRatio).isEqualTo(0.0)
        // A group whose every binding is empty is reported as `0`, not omitted: an omitted group has to
        // keep meaning "not reported", otherwise the mainstream slice would round it up to covered.
        assertThat(coverage.byGroupWithProgrammes).containsExactly(ChannelGroup.SATELLITE, 0)
    }

    @Test
    fun `a caller that only knows the id side keeps the old reading`() {
        // Backward compatibility: the third parameter defaults to the matched set, so every existing
        // call site (and any producer that cannot check the programme table) reads as it always did.
        val channels = listOf(channel(id = 1, name = "CCTV-1", group = ChannelGroup.CCTV))
        val coverage = EpgCoverageCalculator.of(channels, matchedChannelIds = setOf(1))

        assertThat(coverage.withProgrammes).isEqualTo(coverage.matched)
        assertThat(coverage.emptyBinding).isEqualTo(0)
        assertThat(coverage.programmedRatio).isEqualTo(coverage.ratio)
        assertThat(EpgCoverageCalculator.mainstream(coverage).programmedRatio)
            .isEqualTo(EpgCoverageCalculator.mainstream(coverage).ratio)
    }

    @Test
    fun `a programme count for a channel that was never matched cannot inflate the numerator`() {
        val channels = listOf(channel(id = 1, name = "本地台", group = ChannelGroup.LOCAL))
        val coverage = EpgCoverageCalculator.of(
            channels,
            matchedChannelIds = emptySet(),
            // id 1 is known to have programmes, but it has no binding: it is not coverage.
            channelsWithProgrammes = setOf(1),
        )

        assertThat(coverage.matched).isEqualTo(0)
        assertThat(coverage.withProgrammes).isEqualTo(0)
        assertThat(coverage.emptyBinding).isEqualTo(0)
    }
}
