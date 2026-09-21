package ilab.iptv.player.feature.channels

import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.domain.channel.ChannelNumberAssigner
import ilab.iptv.player.core.domain.channel.ChannelNumberSource
import ilab.iptv.player.core.domain.channel.ChannelSorter
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup

/**
 * One line of the browse list: a group header or a channel.
 *
 * The list is flattened on purpose. A RecyclerView of headers plus nested lists would fight over
 * focus and would nest scroll containers — the docs/02 §8.2 focus table wants one focus path
 * (`KEYCODE_DPAD_UP/DOWN` walks the list) and §8.4 needs the whole 658-row list virtualized. A flat
 * `List<ChannelListRow>` with one stable key per row gives both, and DiffUtil then does nothing
 * expensive when a group's channels re-render.
 */
sealed interface ChannelListRow {

    /** Stable id for `RecyclerView.setHasStableIds` / DiffUtil. */
    val key: String

    data class GroupHeader(
        val groupKey: String,
        val title: String,
        val count: Int,
        val group: ChannelGroup,
    ) : ChannelListRow {
        override val key: String get() = "group:$groupKey"
    }

    data class ChannelItem(
        val channelId: Long,
        /** docs/01 D12 channel number (user edit > source `tvg-chno` > automatic). */
        val number: Int,
        val numberSource: ChannelNumberSource,
        val name: String,
        val logoUrl: String?,
        val streamCount: Int,
        val groupTitle: String?,
    ) : ChannelListRow {
        override val key: String get() = "channel:$channelId"

        /** What the logo placeholder shows until real logo loading lands (P1-2 promises a placeholder). */
        val initial: String get() = name.trim().take(1).ifEmpty { "?" }
    }
}

/**
 * Turns a channel list into render rows (docs/01 D12, docs/02 §8.1/§8.2).
 *
 * Order of operations matters and is pinned by tests:
 * 1. [ChannelSorter] orders the whole list (classification → group_key → number → manual order → name);
 * 2. [ChannelNumberAssigner] numbers it, so "automatic numbering by list position" (D12 tier 3) uses
 *    the order the user actually sees, not the order the parser happened to emit;
 * 3. [ChannelGrouping] cuts it into sections and a header is emitted per section.
 */
object ChannelListRows {

    fun build(channels: List<Channel>, userEdits: Map<Long, Int> = emptyMap()): List<ChannelListRow> {
        val ordered = ChannelSorter.sort(channels)
        val numbered = ChannelNumberAssigner.assign(ordered, userEdits).associateBy { it.channel.id }
        val rows = ArrayList<ChannelListRow>(ordered.size + ChannelGrouping.displayOrder.size)
        for (section in ChannelGrouping.sections(ordered)) {
            rows += ChannelListRow.GroupHeader(
                groupKey = section.key,
                title = section.title,
                count = section.channels.size,
                group = section.group,
            )
            for (channel in section.channels) {
                val item = numbered.getValue(channel.id)
                rows += ChannelListRow.ChannelItem(
                    channelId = channel.id,
                    number = item.number,
                    numberSource = item.source,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    streamCount = channel.streamCount,
                    groupTitle = channel.groupTitle,
                )
            }
        }
        return rows
    }
}
