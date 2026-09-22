package ilab.iptv.player.core.domain.epg

import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.Programme

/**
 * The grid's identity hop, in one place: `programme.epg_channel_id` back onto the business channel
 * id the row is keyed on (docs/02 §5.1's two identifiers).
 *
 * `RoomEpgRepository.observeWindow` already makes the forward hop (`channel.id → channel.epg_channel_id`)
 * to build its `IN` list, and it hands the answer back still keyed on the guide id — the caller owns
 * the hop home. That caller is `EpgGridViewModel`, and it used to do it with
 * `associate { epgChannelId to channelId }`, a **one-to-one** map.
 *
 * A guide id is not one-to-one with a business channel and never was: the shipped 658-channel fixture
 * carries `CCTV1`, `CCTV-1综合`, `CCTV1 高清` and `CCTV1 标清`, and the matcher is allowed to bind
 * several of them to the same guide channel (a channel and its HD/sibling feed share one guide). An
 * `associate` keeps only the **last** of them, so every earlier row was handed an empty programme list
 * while `loadedChannelIds` still said "asked and answered" — the row rendered as "no EPG" while the
 * diagnostics panel, which asks per channel by construction, read the same guide id's programmes and
 * reported them. That is BUG-20260922-018's "诊断有节目、网格整行空".
 *
 * So the hop is many-to-many: one guide id maps to **every** channel in the page bound to it, and each
 * of those rows renders the same schedule. `pageChannelIds` is the page the query was issued for, and
 * only channels inside it are mapped — exactly like the one-to-one version, which filtered on
 * `it.id in wanted`.
 */
object EpgChannelHop {

    /**
     * [programmes] is the flat list `EpgRepository.observeWindow` returned for the page's guide ids;
     * the result maps each page channel id to the programmes of the guide id it is bound to. Channels
     * with no binding, or with a binding no returned programme mentions, are absent — the grid reads
     * that as "bound, loaded, nothing in this window", which is a different row from "not loaded".
     */
    fun programmesByChannel(
        pageChannelIds: Collection<Long>,
        channels: List<Channel>,
        programmes: List<Programme>,
    ): Map<Long, List<Programme>> {
        if (pageChannelIds.isEmpty() || programmes.isEmpty()) return emptyMap()
        val wanted = pageChannelIds.toSet()
        val channelIdsByEpgId: Map<String, List<Long>> = channels
            .filter { it.epgChannelId != null && it.id in wanted }
            .groupBy({ it.epgChannelId!! }, { it.id })
        if (channelIdsByEpgId.isEmpty()) return emptyMap()
        val byChannel = LinkedHashMap<Long, MutableList<Programme>>()
        for (programme in programmes) {
            for (channelId in channelIdsByEpgId[programme.epgChannelId] ?: emptyList()) {
                byChannel.getOrPut(channelId) { ArrayList() } += programme
            }
        }
        return byChannel
    }
}
