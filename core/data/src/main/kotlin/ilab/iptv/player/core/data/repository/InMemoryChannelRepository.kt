package ilab.iptv.player.core.data.repository

import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.domain.channel.ChannelSorter
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.ChannelWithStreams
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * docs/02 §4.3 `ChannelRepository` over the in-memory [ChannelStore] (P1-2: no Room).
 *
 * Two deliberate behaviours:
 * - **ordering is the domain's job.** `observe` returns channels in [ChannelSorter] order and each
 *   channel's streams by the §4.3 selection key, so the UI never sorts and two screens cannot
 *   disagree about the order.
 * - **loading is lazy but eager-ish.** `observe` calls `ensureLoaded()` before it emits, so the first
 *   subscriber triggers the parse and every later one just reads state.
 */
@Singleton
class InMemoryChannelRepository @Inject constructor(
    private val store: ChannelStore,
    private val loader: CatalogBootstrapper,
) : ChannelRepository {

    override fun observe(filter: ChannelFilter): Flow<List<ChannelWithStreams>> = flow {
        loader.ensureLoaded()
        emitAll(store.channels.map { channels -> toItems(channels, store.snapshotStreams(), filter) })
    }

    override suspend fun get(channelId: Long): ChannelWithStreams? {
        loader.ensureLoaded()
        val channel = store.channels.value.firstOrNull { it.id == channelId } ?: return null
        return ChannelWithStreams(channel, streamsOf(channel.id))
    }

    override suspend fun setFavorite(channelId: Long, favorite: Boolean) {
        store.updateChannel(channelId) { it.copy(favorite = favorite) }
    }

    override suspend fun setHidden(channelId: Long, hidden: Boolean) {
        store.updateChannel(channelId) { it.copy(hidden = hidden) }
    }

    /**
     * Reorders inside the channel's own `group_key`: the section is re-read in current display
     * order, the channel is pulled out and re-inserted at [newIndex], and `sortOrder` is rewritten
     * from the resulting position. Rewriting the whole section (instead of nudging one row) is what
     * keeps `sortOrder` gapless, so the same move twice is a no-op.
     */
    override suspend fun reorder(channelId: Long, newIndex: Int) {
        val current = store.channels.value
        val channel = current.firstOrNull { it.id == channelId } ?: return
        val section = ChannelSorter.sortWithinGroup(
            current.filter { it.groupKey == channel.groupKey },
        ).toMutableList()
        val from = section.indexOfFirst { it.id == channelId }
        if (from < 0) return
        val target = newIndex.coerceIn(0, section.size - 1)
        section.add(target, section.removeAt(from))
        val reordered = section.mapIndexed { index, item -> item.copy(sortOrder = index) }
        val byId = reordered.associateBy { it.id }
        store.updateChannels { channels -> channels.map { byId[it.id] ?: it } }
    }

    override suspend fun setChannelNo(channelId: Long, channelNo: Int?) {
        store.updateChannel(channelId) { it.copy(channelNo = channelNo) }
    }

    override suspend fun setEpgBinding(channelId: Long, epgChannelId: String?, match: EpgMatchType) {
        store.updateChannel(channelId) { it.copy(epgChannelId = epgChannelId, epgMatch = match) }
    }

    override suspend fun countByGroup(): Map<ChannelGroup, Int> {
        loader.ensureLoaded()
        val counts = LinkedHashMap<ChannelGroup, Int>()
        for (channel in store.channels.value) counts[channel.group] = (counts[channel.group] ?: 0) + 1
        return counts
    }

    private fun toItems(
        channels: List<Channel>,
        streams: List<Stream>,
        filter: ChannelFilter,
    ): List<ChannelWithStreams> {
        val streamsByChannel = streams.groupBy { it.channelId }
        val query = ChannelGrouping.normalizeKey(filter.query)
        val ordered = ChannelSorter.sort(channels)
        val out = ArrayList<ChannelWithStreams>(ordered.size)
        for (channel in ordered) {
            if (!filter.includeHidden && channel.hidden) continue
            if (filter.favoritesOnly && !channel.favorite) continue
            if (filter.group != null && channel.group != filter.group) continue
            if (query.isNotEmpty() && !matches(channel, query)) continue
            out += ChannelWithStreams(channel, orderStreams(streamsByChannel[channel.id].orEmpty()))
        }
        return out
    }

    private fun matches(channel: Channel, query: String): Boolean =
        channel.nameKey.contains(query) || ChannelGrouping.normalizeKey(channel.name).contains(query) ||
            channel.groupKey.contains(query)

    private fun streamsOf(channelId: Long): List<Stream> =
        orderStreams(store.snapshotStreams().filter { it.channelId == channelId })
}

/**
 * docs/02 §4.3 frozen stream key: score desc → priority asc → lastOkAtMs desc (null last) → id asc.
 * Package-level so the channel and stream repositories cannot drift apart on it.
 */
internal fun orderStreams(streams: List<Stream>): List<Stream> = streams.sortedWith(
    compareByDescending<Stream> { it.score }
        .thenBy { it.priority }
        .thenByDescending { it.lastOkAtMs ?: Long.MIN_VALUE }
        .thenBy { it.id },
)
