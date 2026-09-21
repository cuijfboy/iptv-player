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
    /** [EpgMatchType.TVG_ID], [EpgMatchType.NAME_EXACT] or [EpgMatchType.ALIAS] — never NONE. */
    val type: EpgMatchType,
    /**
     * The value that actually matched (the `tvg-id`, the normalized name key, or the alias key). This
     * is what `EPG_MATCH_HIT` logs and what makes "why is this channel on the wrong guide?" answerable
     * without re-running the match (docs/02 §6.3 "匹配结果要能解释").
     */
    val matchedOn: String,
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
 * The match chain of docs/02 §6.3, cut to the three tiers P2-7 owns:
 *
 * 1. **`tvg-id` exact** — the playlist's `tvg-id` equals an XMLTV `<channel id>`. This is the only
 *    tier that is exact by construction, and it is tried first for that reason.
 * 2. **normalized name exact** — `EpgNameKey.key(channel.name)` equals the key of an XMLTV
 *    `<display-name>`. Catches the very common "same channel, different id" case (`CCTV1` vs `CCTV-1`
 *    folds to the same key once width/space/case are normalized).
 * 3. **alias table** — [EpgAliases], for the names normalization cannot bridge.
 *
 * §6.3's tiers ④ (prefix/contains fuzzy) and ⑤ (user manual binding) are deliberately **not** here:
 * the first is P3-5's coverage work and a fuzzy match that is wrong is worse than a miss, and the
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

            val aliasTarget = aliases.targetFor(channel.name) ?: aliases.targetFor(key)
            val aliasId = aliasTarget?.let { resolveAlias(it, index, knownIds) }
            if (aliasTarget != null && aliasId != null) {
                hits += EpgMatchResult(
                    channelId = channel.id,
                    epgChannelId = aliasId,
                    type = EpgMatchType.ALIAS,
                    matchedOn = nameKey(channel.name).ifEmpty { key },
                )
                continue
            }

            misses += EpgMiss(channelId = channel.id, nameKey = key, triedTvgId = tvgId)
        }
        return EpgMatchReport(hits = hits, misses = misses, preserved = preserved)
    }

    /**
     * An alias target is either the guide's channel id or a channel name. Trying the id first matters:
     * a name-shaped target that happens to equal some id would otherwise bind to the wrong channel.
     */
    private fun resolveAlias(target: String, index: EpgChannelIndex, knownIds: Set<String>): String? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed in knownIds) return index.byId[trimmed] ?: index.byNameKey[nameKey(trimmed)] ?: trimmed
        return index.byNameKey[nameKey(trimmed)]
    }
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

    fun of(channels: List<Channel>, matchedChannelIds: Set<Long>): EpgCoverage {
        var matched = 0
        val byGroup = HashMap<ChannelGroup, Int>()
        for (channel in channels) {
            if (channel.id in matchedChannelIds) {
                matched++
                byGroup[channel.group] = (byGroup[channel.group] ?: 0) + 1
            }
        }
        return EpgCoverage(
            matched = matched,
            total = channels.size,
            byGroup = byGroup,
        )
    }
}
