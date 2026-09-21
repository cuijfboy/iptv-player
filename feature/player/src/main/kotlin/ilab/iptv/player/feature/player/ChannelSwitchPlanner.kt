package ilab.iptv.player.feature.player

import ilab.iptv.player.core.domain.channel.ChannelNumberAssigner
import ilab.iptv.player.core.domain.channel.ChannelSorter
import ilab.iptv.player.core.domain.channel.NumberedChannel
import ilab.iptv.player.core.model.Channel

/**
 * Channel switching inside the player (P1-5 item 1: "上下键换台（同一分组内顺序）、数字键直跳").
 *
 * Pure, so the two behaviours the remote drives are pinned by tests instead of by watching a screen.
 *
 * ORDER: the list is ordered and numbered exactly like the browse screen (`ChannelSorter` then
 * `ChannelNumberAssigner`, docs/01 D12 / docs/02 §8.1) — "the next channel" must mean the channel
 * below the current one *in the list the user just left*, not a second ordering invented here.
 *
 * GROUP EDGES CLAMP: UP on the last channel of a group stays put. Wrapping would jump from the end of
 * "央视" into "地方" with one press, which is not "同一分组内顺序"; the browse screen is the way to
 * change groups.
 *
 * `delta = +1` is the next (higher) channel, which is what `KEYCODE_DPAD_UP` / `KEYCODE_CHANNEL_UP`
 * mean on a TV remote; `delta = -1` is the previous one.
 */
object ChannelSwitchPlanner {

    /** Sort + number a channel list the way the browse screen does (docs/01 D12). */
    fun numbered(channels: List<Channel>): List<NumberedChannel> =
        ChannelNumberAssigner.assign(ChannelSorter.sort(channels))

    /**
     * The channel [delta] steps away from [currentId] inside its own group, or null when there is
     * none (unknown current channel, or the group edge).
     */
    fun neighbor(numbered: List<NumberedChannel>, currentId: Long, delta: Int): Channel? {
        if (delta == 0) return null
        val current = numbered.firstOrNull { it.channel.id == currentId } ?: return null
        val group = numbered.filter { it.channel.groupKey == current.channel.groupKey }
        val index = group.indexOfFirst { it.channel.id == currentId }
        if (index < 0) return null
        return group.getOrNull(index + delta)?.channel
    }

    /** The channel carrying the number the user typed (docs/02 §8.2 数字键跳台), or null. */
    fun byNumber(numbered: List<NumberedChannel>, number: Int): Channel? =
        numbered.firstOrNull { it.number == number }?.channel
}
