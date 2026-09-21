package ilab.iptv.player.core.domain.channel

import ilab.iptv.player.core.model.Channel

/**
 * Channel ordering for the browse list (docs/02 §8.1/§8.2). Pure Kotlin policy; the docs fix the
 * *list* contract ("分组 Tab + 列表", "上下键在列表内移动") but never spell out a sort key, so this
 * package owns one and the P1-2 record writes it down:
 *
 * 1. classification display order ([ChannelGrouping.displayOrder] — 央视 → 卫视 → 港澳台 → 本地 → 其他);
 * 2. `group_key` ascending, so sections render in a stable order inside one classification;
 * 3. [Channel.channelNo] ascending, **nulls last** (a channel with no number is not "channel 0");
 * 4. [Channel.sortOrder] ascending (the user's manual order, docs/01 F5);
 * 5. [Channel.nameKey] ascending, then [Channel.name], then [Channel.id] — a total order, so the
 *    list never reshuffles between two identical refreshes.
 *
 * The comparator is deliberately a *display* order: it does not move streams, ids or EPG bindings.
 */
object ChannelSorter {

    val comparator: Comparator<Channel> = compareBy(
        { ChannelGrouping.rank(it.group) },
        { it.groupKey },
        { it.channelNo ?: Int.MAX_VALUE },
        { it.sortOrder },
        { it.nameKey },
        { it.name },
        { it.id },
    )

    /** The whole list, ordered for rendering. Stable: equal keys keep their input order. */
    fun sort(channels: List<Channel>): List<Channel> = channels.sortedWith(comparator)

    /** One group's channels, ordered for rendering inside its section. */
    fun sortWithinGroup(channels: List<Channel>): List<Channel> = sort(channels)
}
