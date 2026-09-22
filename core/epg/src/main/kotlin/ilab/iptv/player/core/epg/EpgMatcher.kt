package ilab.iptv.player.core.epg

import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgChannelIndex
import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgMatchType

/** One channel that now has an EPG channel id, and how it got it. */
data class EpgMatchResult(
    val channelId: Long,
    val epgChannelId: String,
    /**
     * [EpgMatchType.TVG_ID], [EpgMatchType.NAME_EXACT], [EpgMatchType.NAME_FUZZY] or
     * [EpgMatchType.ALIAS] — never NONE.
     */
    val type: EpgMatchType,
    /**
     * The value on the **channel** side that matched: the `tvg-id`, the normalized name key, the
     * variant key of [EpgNameVariants] (a script/feed/punctuation fold), or the alias key. This is
     * what `EPG_MATCH_HIT` logs and what makes "why is this channel on the wrong guide?" answerable
     * without re-running the match (docs/02 §6.3 "匹配结果要能解释").
     */
    val matchedOn: String,
    /**
     * The guide-side key the hit resolved to, when it came through the name index (`NAME_EXACT` /
     * `NAME_FUZZY` / a name-shaped alias target). Null for a `tvg-id` hit or a numeric alias target.
     * Logged next to [matchedOn] so a fuzzy hit shows *both* sides of the fold — including a
     * Traditional guide key, which is never rewritten (folding is a lookup key, not a display name).
     */
    val guideKey: String? = null,
)

/** A channel no tier could bind. `triedTvgId` is logged so a *wrong* `tvg-id` is visible, not just a missing one. */
data class EpgMiss(
    val channelId: Long,
    val nameKey: String,
    val triedTvgId: String?,
)

/**
 * The outcome of one match pass. [preserved] are channels whose `MANUAL` binding was left alone
 * (§6.3: a user binding is never overwritten) — reported separately from [hits] on purpose, because
 * counting them as matches would overstate what the automatic chain achieved.
 */
data class EpgMatchReport(
    val hits: List<EpgMatchResult>,
    val misses: List<EpgMiss>,
    val preserved: List<Long>,
) {
    /** Coverage of the automatic chain over the channels it was allowed to touch. */
    val attempted: Int get() = hits.size + misses.size
}

/**
 * The match chain of docs/02 §6.3, cut to the four tiers P2-7 + P3-5 own:
 *
 * 1. **`tvg-id` exact** — the playlist's `tvg-id` equals an XMLTV `<channel id>`. This is the only
 *    tier that is exact by construction, and it is tried first for that reason.
 * 2. **normalized name exact** — `EpgNameKey.key(channel.name)` equals the key of an XMLTV
 *    `<display-name>`. Catches the very common "same channel, different id" case (`CCTV1` vs `CCTV-1`
 *    folds to the same key once width/space/case are normalized).
 * 3. **normalized name, folded** — [EpgNameVariants]: script ([EpgTraditionalFold], `澳視澳門` vs
 *    `澳视澳门`), feed markers (`CCTV1 高清`), punctuation (`CCTV-1综合`) and `+` (`CCTV5+`, guide:
 *    `CCTV-5+ 体育赛事`) are the same channel as their plain forms, in that rule order. Still an
 *    equality test on both sides, so it cannot bind a channel to a *neighbouring* channel.
 * 4. **alias table** — [EpgAliases], for the names normalization cannot bridge (`央视新闻` →
 *    `CCTV-13 新闻`, `凤凰卫视中文台` → the guide's `凤凰中文`). Alias lookups also try the tier-3
 *    variant keys, so `福建东南卫视 高清` reaches the `福建东南卫视` entry.
 *
 * §6.3's ③ (prefix/contains fuzzy) and ⑤ (user manual binding) are deliberately **not** here: the
 * first is a match that can be wrong and is worse than a miss (measured: +4 channels on the shipped
 * fixture, all in the synthetic local bucket — `docs/05-过程记录/37-P3-5EPG覆盖率.md` §5), and the
 * second is not a match at all — [EpgMatcher] only *respects* it (a channel whose binding is already
 * [EpgMatchType.MANUAL] with an id is returned in [EpgMatchReport.preserved] and never re-matched).
 *
 * Pure and injectable: the name key is a parameter so production can pass `:core:source`'s
 * `Keys::nameKey` (`:core:data` wires it, `NameKeyParityTest` pins the two together) while the module
 * itself keeps its own default.
 */
class EpgMatcher(
    private val nameKey: (String?) -> String = EpgNameKey::key,
    private val aliases: EpgAliases = EpgAliases.BUILT_IN,
) {

    fun match(channels: List<Channel>, index: EpgChannelIndex): EpgMatchReport {
        val knownIds: Set<String> = index.epgChannelIds()
        // Tier 3's lookup table. Built from the guide's name keys (not from the guide itself) so the
        // matcher stays a pure function of the index, and rebuilt per pass because a pass is
        // per-source and the index is the source's.
        val variantIndex = EpgNameVariants.index(index.byNameKey)
        val hits = ArrayList<EpgMatchResult>(channels.size)
        val misses = ArrayList<EpgMiss>()
        val preserved = ArrayList<Long>()

        for (channel in channels) {
            // ⑤ outranks everything: a manual binding is a user decision (docs/01 F5).
            if (channel.epgMatch == EpgMatchType.MANUAL && !channel.epgChannelId.isNullOrBlank()) {
                preserved += channel.id
                continue
            }

            val tvgId = channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }
            val byId = tvgId?.let { index.byId[it] }
            if (byId != null) {
                hits += EpgMatchResult(channel.id, byId, EpgMatchType.TVG_ID, matchedOn = tvgId)
                continue
            }

            val key = channel.nameKey.ifEmpty { nameKey(channel.name) }
            val byName = key.takeIf { it.isNotEmpty() }?.let { index.byNameKey[it] }
            if (byName != null) {
                hits += EpgMatchResult(channel.id, byName, EpgMatchType.NAME_EXACT, matchedOn = key)
                continue
            }

            // ③ feed/punctuation-folded name: same key after EpgNameVariants.canonical, reported with
            // the variant that hit so the fold is visible in `EPG_MATCH_HIT`.
            val variantHit = variantHit(key, variantIndex)
            if (variantHit != null) {
                hits += EpgMatchResult(
                    channelId = channel.id,
                    epgChannelId = variantHit.hit.epgChannelId,
                    type = EpgMatchType.NAME_FUZZY,
                    matchedOn = variantHit.variant,
                    guideKey = variantHit.hit.guideKey,
                )
                continue
            }

            // ④ alias table, on the plain key first and then on the same variant keys — a source that
            // writes `福建东南卫视 高清` must not lose the `福建东南卫视` entry.
            val alias = aliasLookup(channel.name, key)
            val resolvedAlias = alias?.let { resolveAlias(it.target, index, knownIds, variantIndex) }
            if (alias != null && resolvedAlias != null) {
                hits += EpgMatchResult(
                    channelId = channel.id,
                    epgChannelId = resolvedAlias.epgChannelId,
                    type = EpgMatchType.ALIAS,
                    matchedOn = alias.key,
                    guideKey = resolvedAlias.guideKey,
                )
                continue
            }

            misses += EpgMiss(channelId = channel.id, nameKey = key, triedTvgId = tvgId)
        }
        return EpgMatchReport(hits = hits, misses = misses, preserved = preserved)
    }

    /**
     * The first form of `key` a guide owns, *including the key itself*: the guide side is expanded, so
     * a guide that writes `CCTV-8K` registers `cctv8k`, and a playlist that writes `CCTV8K` finds it
     * here — tier 2 could not, because tier 2 compares against the guide's **unfolded** name keys.
     */
    private fun variantHit(key: String, variantIndex: Map<String, EpgNameVariants.VariantHit>): Hit? {
        if (key.isEmpty()) return null
        for (variant in EpgNameVariants.variants(key)) {
            val hit = variantIndex[variant] ?: continue
            return Hit(variant, hit)
        }
        return null
    }

    private data class Hit(val variant: String, val hit: EpgNameVariants.VariantHit)

    private data class AliasLookup(val key: String, val target: String)

    private fun aliasLookup(channelName: String, key: String): AliasLookup? {
        val direct = aliases.targetFor(channelName) ?: aliases.targetFor(key)
        if (direct != null) return AliasLookup(key, direct)
        if (key.isEmpty()) return null
        for (variant in EpgNameVariants.variants(key)) {
            val target = aliases.targetFor(variant) ?: continue
            return AliasLookup(variant, target)
        }
        return null
    }

    /**
     * An alias target is either the guide's channel id or a channel name. Trying the id first matters:
     * a name-shaped target that happens to equal some id would otherwise bind to the wrong channel.
     */
    private fun resolveAlias(
        target: String,
        index: EpgChannelIndex,
        knownIds: Set<String>,
        variantIndex: Map<String, EpgNameVariants.VariantHit>,
    ): ResolvedAlias? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed in knownIds) {
            val byId = index.byId[trimmed]
            if (byId != null) return ResolvedAlias(byId, guideKey = null)
            val guideKey = nameKey(trimmed)
            return ResolvedAlias(index.byNameKey[guideKey] ?: trimmed, guideKey)
        }
        val guideKey = nameKey(trimmed)
        val byName = index.byNameKey[guideKey]
        if (byName == null) {
            // The guide spells the target with a feed marker or punctuation the alias entry dropped:
            // resolve it through the same variant table tier 3 uses, so one entry keeps working
            // across sources (`凤凰中文` vs `凤凰中文 高清`).
            val variant = variantHit(guideKey, variantIndex) ?: return null
            return ResolvedAlias(variant.hit.epgChannelId, variant.hit.guideKey)
        }
        return ResolvedAlias(byName, guideKey)
    }

    private data class ResolvedAlias(val epgChannelId: String, val guideKey: String?)
}

/** Every EPG channel id the guide declared, from either half of the index. */
fun EpgChannelIndex.epgChannelIds(): Set<String> = byId.values.toSet() + byNameKey.values.toSet()

/**
 * Builds the index from the `<channel>` elements the parser emitted: the id is its own lookup key
 * (XMLTV's `<channel id>` *is* the `tvg-id` the playlist carries), and each display name gets a
 * normalized key. Later duplicates lose — a guide that lists a name twice cannot make the second one
 * shadow the first, and reported order is stable.
 */
fun epgChannelIndex(
    channels: List<XmltvChannel>,
    nameKey: (String?) -> String = EpgNameKey::key,
): EpgChannelIndex {
    val byId = LinkedHashMap<String, String>(channels.size)
    val byNameKey = LinkedHashMap<String, String>()
    for (channel in channels) {
        val id = channel.id.trim()
        if (id.isEmpty()) continue
        // `Map.putIfAbsent` is API 24 and this module supports API 21 (docs/02 §14), so first-wins is
        // spelled out. `LinkedHashMap` keeps the reported order stable, which the tests rely on.
        if (id !in byId) byId[id] = id
        for (displayName in channel.displayNames) {
            val key = nameKey(displayName)
            if (key.isNotEmpty() && key !in byNameKey) byNameKey[key] = id
        }
    }
    return EpgChannelIndex(byId = byId, byNameKey = byNameKey)
}

/** The coverage rule of §6.3, over channels that have been matched: matched / total, split by group. */
object EpgCoverageCalculator {

    /**
     * docs/04's P3-5 exit criterion is "≥60%（主流频道）" and never defines 主流. This module owns the
     * definition because it owns the coverage rule: the mainstream slice is 央视 + 卫视 + 港澳台 —
     * the channels a buyer of the app is checking when they say "覆盖率". The 地方/其他 long tail is
     * reported separately and *not* folded in: on the shipped 658-channel fixture 500 of the channels
     * are local, most of which no public guide carries, so a single overall ratio would hide both a
     * perfect CCTV result and a broken satellite one. **The definition is a口径 question for god/arch**
     * (docs/01–04 are frozen); this round reports both numbers so either choice can be read out.
     */
    val MAINSTREAM_GROUPS: List<ChannelGroup> =
        listOf(ChannelGroup.CCTV, ChannelGroup.SATELLITE, ChannelGroup.HK_MO_TW)

    /** One slice of the coverage report: matched / total for a set of groups. */
    data class Slice(val matched: Int, val total: Int) {
        val ratio: Double get() = if (total <= 0) 0.0 else matched.toDouble() / total
    }

    fun of(channels: List<Channel>, matchedChannelIds: Set<Long>): EpgCoverage {
        var matched = 0
        val byGroup = HashMap<ChannelGroup, Int>()
        val byGroupTotal = HashMap<ChannelGroup, Int>()
        for (channel in channels) {
            byGroupTotal[channel.group] = (byGroupTotal[channel.group] ?: 0) + 1
            if (channel.id in matchedChannelIds) {
                matched++
                byGroup[channel.group] = (byGroup[channel.group] ?: 0) + 1
            }
        }
        return EpgCoverage(
            matched = matched,
            total = channels.size,
            byGroup = byGroup,
            byGroupTotal = byGroupTotal,
        )
    }

    /** The mainstream slice of [coverage] — what docs/04's P3-5 "≥60%" is measured on. */
    fun mainstream(coverage: EpgCoverage): Slice {
        var matched = 0
        var total = 0
        for (group in MAINSTREAM_GROUPS) {
            matched += coverage.byGroup[group] ?: 0
            total += coverage.byGroupTotal[group] ?: 0
        }
        return Slice(matched = matched, total = total)
    }
}
