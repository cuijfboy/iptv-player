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
 *
 * [hits] holds **one entry per candidate**, and EPG-BIND made that more than one per channel: a
 * channel can be proposed by several tiers of the same guide (`CCTV2` matches the guide's `CCTV2`
 * stub by name and its `CCTV-2 财经` entry by the numbered handle). The binding choice is what picks
 * one; the matcher reports every justification it found.
 */
data class EpgMatchReport(
    val hits: List<EpgMatchResult>,
    val misses: List<EpgMiss>,
    val preserved: List<Long>,
) {
    /**
     * Channels the automatic chain was allowed to touch: one per channel with at least one candidate,
     * plus every miss. Counted by channel, not by candidate, so adding a tier's second proposal to a
     * channel does not inflate it.
     */
    val attempted: Int get() = hits.distinctBy { it.channelId }.size + misses.size
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
 * **Every tier is a candidate (EPG-BIND).** The chain used to `continue` on its first hit, so a
 * channel the guide spelled two ways only ever saw one of them and the alias tier was dead code for
 * any channel that matched earlier. Each tier now *proposes*; its proposals are deduplicated by guide
 * id (keeping the earliest tier as the explanation) and handed to `EpgBindingPreference`, which picks
 * the id with the most programmes in the retention window. That is what turns "the first tier that
 * hit wins" into "the guide id that actually has data wins" — see
 * `docs/05-过程记录/43-EPG匹配层候选化.md`.
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

            // One proposal per guide id, first tier wins the explanation. `LinkedHashMap` (not
            // `putIfAbsent`, which is API 24 and this module supports API 21) keeps the order stable.
            val candidates = LinkedHashMap<String, EpgMatchResult>()
            fun offer(result: EpgMatchResult) {
                if (result.epgChannelId !in candidates) candidates[result.epgChannelId] = result
            }

            val tvgId = channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }
            val byId = tvgId?.let { index.byId[it] }
            if (tvgId != null && byId != null) {
                offer(EpgMatchResult(channel.id, byId, EpgMatchType.TVG_ID, matchedOn = tvgId))
            }

            val key = channel.nameKey.ifEmpty { nameKey(channel.name) }
            if (key.isNotEmpty()) {
                // ② every id the guide declares under this exact name. More than one is normal: a guide
                // that lists a name twice (52 such groups on the shipped one) has an id per declaration.
                for (id in index.byNameKey[key].orEmpty()) {
                    offer(EpgMatchResult(channel.id, id, EpgMatchType.NAME_EXACT, matchedOn = key))
                }
                // ③ feed/punctuation/script/number-folded name: reported with the variant that hit so
                // the fold is visible in `EPG_MATCH_HIT`.
                for (hit in variantHits(key, variantIndex)) {
                    offer(
                        EpgMatchResult(
                            channelId = channel.id,
                            epgChannelId = hit.hit.epgChannelId,
                            type = EpgMatchType.NAME_FUZZY,
                            matchedOn = hit.variant,
                            guideKey = hit.hit.guideKey,
                        ),
                    )
                }
            }

            // ④ alias table, on the plain key first and then on the same variant keys — a source that
            // writes `福建东南卫视 高清` must not lose the `福建东南卫视` entry.
            val alias = aliasLookup(channel.name, key)
            if (alias != null) {
                for (resolved in resolveAlias(alias.target, index, knownIds, variantIndex)) {
                    offer(
                        EpgMatchResult(
                            channelId = channel.id,
                            epgChannelId = resolved.epgChannelId,
                            type = EpgMatchType.ALIAS,
                            matchedOn = alias.key,
                            guideKey = resolved.guideKey,
                        ),
                    )
                }
            }

            if (candidates.isEmpty()) {
                misses += EpgMiss(channelId = channel.id, nameKey = key, triedTvgId = tvgId)
            } else {
                hits += candidates.values
            }
        }
        return EpgMatchReport(hits = hits, misses = misses, preserved = preserved)
    }

    /**
     * Every `(variant, guide channel)` pair the guide can justify for `key`, *including the key
     * itself*: the guide side is expanded, so a guide that writes `CCTV-8K` registers `cctv8k`, and a
     * playlist that writes `CCTV8K` finds it here — tier 2 could not, because tier 2 compares against
     * the guide's **unfolded** name keys. Variants are walked least-mutated first, so the first pair
     * reported is the smallest change that explains the hit.
     */
    private fun variantHits(
        key: String,
        variantIndex: Map<String, List<EpgNameVariants.VariantHit>>,
    ): List<Hit> {
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Hit>(4)
        for (variant in EpgNameVariants.variants(key)) {
            for (hit in variantIndex[variant].orEmpty()) out += Hit(variant, hit)
        }
        return out
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
     * Every id the target stands for is returned — an alias does not pick a winner either.
     */
    private fun resolveAlias(
        target: String,
        index: EpgChannelIndex,
        knownIds: Set<String>,
        variantIndex: Map<String, List<EpgNameVariants.VariantHit>>,
    ): List<ResolvedAlias> {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed in knownIds) {
            val byId = index.byId[trimmed]
            if (byId != null) return listOf(ResolvedAlias(byId, guideKey = null))
            val guideKey = nameKey(trimmed)
            return listOf(ResolvedAlias(index.byNameKey[guideKey]?.firstOrNull() ?: trimmed, guideKey))
        }
        val guideKey = nameKey(trimmed)
        val byName = index.byNameKey[guideKey]
        if (byName != null) return byName.map { ResolvedAlias(it, guideKey) }
        // The guide spells the target with a feed marker or punctuation the alias entry dropped:
        // resolve it through the same variant table tier 3 uses, so one entry keeps working
        // across sources (`凤凰中文` vs `凤凰中文 高清`).
        return variantHits(guideKey, variantIndex)
            .map { ResolvedAlias(it.hit.epgChannelId, it.hit.guideKey) }
    }

    private data class ResolvedAlias(val epgChannelId: String, val guideKey: String?)
}

/** Every EPG channel id the guide declared, from either half of the index. */
fun EpgChannelIndex.epgChannelIds(): Set<String> =
    byId.values.toSet() + byNameKey.values.flatten().toSet()

/**
 * Builds the index from the `<channel>` elements the parser emitted: the id is its own lookup key
 * (XMLTV's `<channel id>` *is* the `tvg-id` the playlist carries), and each display name gets a
 * normalized key. A name can hold **several** ids and keeps them all in guide order (EPG-BIND): a
 * guide that lists a name twice has two ids behind it and the binding choice, not this map, decides
 * which one the channel gets. Reported order stays stable for the tests.
 */
fun epgChannelIndex(
    channels: List<XmltvChannel>,
    nameKey: (String?) -> String = EpgNameKey::key,
): EpgChannelIndex {
    val byId = LinkedHashMap<String, String>(channels.size)
    val byNameKey = LinkedHashMap<String, List<String>>()
    for (channel in channels) {
        val id = channel.id.trim()
        if (id.isEmpty()) continue
        // `Map.putIfAbsent` is API 24 and this module supports API 21 (docs/02 §14), so first-wins is
        // spelled out. `LinkedHashMap` keeps the reported order stable, which the tests rely on.
        if (id !in byId) byId[id] = id
        for (displayName in channel.displayNames) {
            val key = nameKey(displayName)
            if (key.isEmpty()) continue
            val existing = byNameKey[key].orEmpty()
            if (id !in existing) byNameKey[key] = existing + id
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

    /**
     * One slice of the coverage report: matched / total for a set of groups, and the sub-count that
     * actually has programmes. [withProgrammes] defaults to [matched] so a caller that only knows the
     * id side reads exactly as it did before the second口径 existed.
     */
    data class Slice(val matched: Int, val total: Int, val withProgrammes: Int = matched) {
        val ratio: Double get() = if (total <= 0) 0.0 else matched.toDouble() / total

        /** Matched channels whose binding holds nothing — counted as covered before, blank in the app. */
        val emptyBinding: Int get() = (matched - withProgrammes).coerceAtLeast(0)

        /** The reading the target gate uses: a binding that holds no programme is not coverage. */
        val programmedRatio: Double get() = if (total <= 0) 0.0 else withProgrammes.toDouble() / total
    }

    /**
     * @param channelsWithProgrammes the subset of [matchedChannelIds] whose *chosen* guide id holds at
     *   least one programme in the retention window. It defaults to [matchedChannelIds], i.e. "every
     *   binding has programmes" — the pre-EPG-BIND assumption, which keeps callers that cannot check
     *   (and the older tests) byte-identical.
     */
    fun of(
        channels: List<Channel>,
        matchedChannelIds: Set<Long>,
        channelsWithProgrammes: Set<Long> = matchedChannelIds,
    ): EpgCoverage {
        var matched = 0
        var withProgrammes = 0
        val byGroup = HashMap<ChannelGroup, Int>()
        val byGroupTotal = HashMap<ChannelGroup, Int>()
        val byGroupWithProgrammes = HashMap<ChannelGroup, Int>()
        for (channel in channels) {
            byGroupTotal[channel.group] = (byGroupTotal[channel.group] ?: 0) + 1
            if (channel.id in matchedChannelIds) {
                matched++
                byGroup[channel.group] = (byGroup[channel.group] ?: 0) + 1
                // A group with matched channels always gets an entry, even a zero one. Without that,
                // "this group's bindings are all empty" and "this producer never reported the
                // programmed side" would both be an absent key, and a consumer could only fall back to
                // assuming the empties were covered — the very bug EPG-BIND exists to fix.
                if (!byGroupWithProgrammes.containsKey(channel.group)) {
                    byGroupWithProgrammes[channel.group] = 0
                }
                // Guarded by `matchedChannelIds`: a channel that is known to have programmes but was
                // never matched would otherwise make the numerator exceed the denominator.
                if (channel.id in channelsWithProgrammes) {
                    withProgrammes++
                    byGroupWithProgrammes[channel.group] = (byGroupWithProgrammes[channel.group] ?: 0) + 1
                }
            }
        }
        return EpgCoverage(
            matched = matched,
            total = channels.size,
            byGroup = byGroup,
            byGroupTotal = byGroupTotal,
            withProgrammes = withProgrammes,
            byGroupWithProgrammes = byGroupWithProgrammes,
        )
    }

    /** The mainstream slice of [coverage] — what docs/04's P3-5 "≥60%" is measured on. */
    fun mainstream(coverage: EpgCoverage): Slice {
        var matched = 0
        var total = 0
        var withProgrammes = 0
        for (group in MAINSTREAM_GROUPS) {
            val groupMatched = coverage.byGroup[group] ?: 0
            matched += groupMatched
            total += coverage.byGroupTotal[group] ?: 0
            // A producer that filled only the matched side (an older caller, or the id-only read path)
            // is read as "everything it matched has programmes", so its slice does not shift. A group
            // the producer *did* report carries its own number, zero included — which is how an
            // all-empty group reaches this slice instead of being rounded up (see `of`).
            withProgrammes += coverage.byGroupWithProgrammes[group] ?: groupMatched
        }
        return Slice(matched = matched, total = total, withProgrammes = withProgrammes)
    }
}
