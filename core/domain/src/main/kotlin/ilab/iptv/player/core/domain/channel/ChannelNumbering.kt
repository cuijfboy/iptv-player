package ilab.iptv.player.core.domain.channel

import ilab.iptv.player.core.model.Channel

/**
 * Where a channel number came from. docs/01 D12 fixes the *precedence* (user edit > source
 * `tvg-chno` > automatic numbering by list position); the P1-2 stage has no user edits yet (that is
 * P2-2), but the enum already carries the top tier so the call site does not change when it lands.
 */
enum class ChannelNumberSource { USER_EDIT, SOURCE_TVG_CHNO, AUTO }

/** A channel plus the number it is displayed under and why (docs/01 D12). */
data class NumberedChannel(
    val channel: Channel,
    val number: Int,
    val source: ChannelNumberSource,
)

/**
 * D12 channel-number assignment (docs/01 §5 D12, docs/02 §8.2).
 *
 * Rules, in precedence order:
 * 1. **user edit** ([userEdits], keyed by [Channel.id]) — not produced before P2-2, but honored here
 *    so a later stage cannot forget the top tier;
 * 2. **source `tvg-chno`** — [Channel.channelNo] as the source declared it; kept verbatim, including
 *    when two channels declare the same number (real playlists do; docs/02 fixes no collision rule
 *    for it, so "do not invent one" is the safe reading — see §12 of the P1-2 record);
 * 3. **automatic numbering by list position** — the smallest positive integer not already taken,
 *    walking the input order, so a refresh that keeps the same order keeps the same numbers.
 *
 * The scan cursor only moves forward, so automatic numbers never collide with each other; numbers
 * already claimed by tiers 1–2 are skipped. The output is in input order, so callers can zip it with
 * `ChannelGrouping.sections` without re-sorting.
 */
object ChannelNumberAssigner {

    fun assign(channels: List<Channel>, userEdits: Map<Long, Int> = emptyMap()): List<NumberedChannel> {
        val taken = HashSet<Int>()
        val pending = ArrayList<Channel>()
        val byId = HashMap<Long, NumberedChannel>(channels.size)

        for (channel in channels) {
            val userEdited = userEdits[channel.id]
            val sourceNumber = channel.channelNo?.takeIf { it > 0 }
            when {
                userEdited != null && userEdited > 0 -> {
                    taken += userEdited
                    byId[channel.id] = NumberedChannel(channel, userEdited, ChannelNumberSource.USER_EDIT)
                }
                sourceNumber != null -> {
                    taken += sourceNumber
                    byId[channel.id] = NumberedChannel(channel, sourceNumber, ChannelNumberSource.SOURCE_TVG_CHNO)
                }
                else -> pending += channel
            }
        }

        var cursor = 1
        for (channel in pending) {
            while (taken.contains(cursor)) cursor++
            taken += cursor
            byId[channel.id] = NumberedChannel(channel, cursor, ChannelNumberSource.AUTO)
            cursor++
        }

        return channels.map { channel -> byId.getValue(channel.id) }
    }
}
