package ilab.iptv.player.core.data.repository

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.data.catalog.RoomCatalogSeeder
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.ChannelOrder
import ilab.iptv.player.core.database.dao.ChannelWithStreamRows
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
 * docs/02 §4.3 `ChannelRepository`, backed by Room (P2-1). It is a drop-in replacement for
 * [InMemoryChannelRepository]: the same ordering promises, the same filters, the same mutations — only
 * the storage changed, which is exactly what the frozen port bought us.
 *
 * Division of labour between SQL and Kotlin, decided per query and stated once here:
 * - **SQL** does what an index in §5.1 covers: the sectioned read order (`idx_channel_group`), the
 *   hidden/favourite predicates, the identity lookups (`idx_channel_namekey`) and every write.
 * - **Kotlin** does what SQL cannot express faithfully: the coarse [ChannelGroup] classification
 *   (one enum value maps to many `group_key` values, so "filter by group" is not a column comparison)
 *   and the display order of [ChannelSorter], which is the list's contract with the UI.
 *
 * `observe` seeds the database on first use ([RoomCatalogSeeder]); after that a cold start reads the
 * channel list straight out of SQLite.
 */
@Singleton
class RoomChannelRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val seeder: RoomCatalogSeeder,
    private val clock: Clock,
) : ChannelRepository {

    override fun observe(filter: ChannelFilter): Flow<List<ChannelWithStreams>> = flow {
        seeder.ensureSeeded()
        val query = filter.query?.let(ChannelGrouping::normalizeKey)?.takeIf(String::isNotEmpty)
        emitAll(
            channelDao
                .observeFiltered(
                    includeHidden = filter.includeHidden,
                    favoritesOnly = filter.favoritesOnly,
                    groupKey = null,
                    query = query,
                )
                .map { rows -> rows.mapToItems().filterByGroup(filter.group) },
        )
    }

    override suspend fun get(channelId: Long): ChannelWithStreams? {
        seeder.ensureSeeded()
        val row = channelDao.getWithStreams(channelId) ?: return null
        val (channel, streams) = PersistenceMapper.toDomain(row)
        return ChannelWithStreams(channel, orderStreams(streams))
    }

    override suspend fun setFavorite(channelId: Long, favorite: Boolean) {
        channelDao.setFavorite(channelId, favorite, clock.nowMs())
    }

    override suspend fun setHidden(channelId: Long, hidden: Boolean) {
        channelDao.setHidden(channelId, hidden, clock.nowMs())
    }

    /**
     * Same contract as the in-memory implementation: the move happens inside the channel's own
     * `group_key`, the whole section's `sort_order` is rewritten from the resulting position (so the
     * sequence stays gapless and repeating a move is a no-op), and the rewrite is one transaction.
     */
    override suspend fun reorder(channelId: Long, newIndex: Int) {
        val target = channelDao.getWithStreams(channelId)?.channel ?: return
        val section = channelDao.byGroup(target.groupKey).map { PersistenceMapper.toDomain(it) }
        val ordered = ChannelSorter.sortWithinGroup(section).toMutableList()
        val from = ordered.indexOfFirst { it.id == channelId }
        if (from < 0) return
        val to = newIndex.coerceIn(0, ordered.size - 1)
        ordered.add(to, ordered.removeAt(from))
        channelDao.setSortOrders(
            ordered.mapIndexed { index, channel -> ChannelOrder(channel.id, index) },
            clock.nowMs(),
        )
    }

    override suspend fun setChannelNo(channelId: Long, channelNo: Int?) {
        channelDao.setChannelNo(channelId, channelNo, clock.nowMs())
    }

    override suspend fun setEpgBinding(channelId: Long, epgChannelId: String?, match: EpgMatchType) {
        channelDao.setEpgBinding(channelId, epgChannelId, match.name, clock.nowMs())
    }

    /**
     * The coarse classification is recovered from `group_key` because `group_key` *is* the normalized
     * group title and the classifier normalizes its input (see `PersistenceMapper`), so summing the
     * `GROUP BY group_key` counts per classification gives exactly what the in-memory map gave.
     */
    override suspend fun countByGroup(): Map<ChannelGroup, Int> {
        seeder.ensureSeeded()
        val counts = LinkedHashMap<ChannelGroup, Int>()
        for (row in channelDao.countByGroupKey()) {
            val group = ChannelGrouping.classify(row.groupKey)
            counts[group] = (counts[group] ?: 0) + row.count
        }
        return counts
    }

    private fun List<ChannelWithStreamRows>.mapToItems(): List<ChannelWithStreams> = map { row ->
        val (channel, streams) = PersistenceMapper.toDomain(row)
        ChannelWithStreams(channel, orderStreams(streams))
    }

    private fun List<ChannelWithStreams>.filterByGroup(group: ChannelGroup?): List<ChannelWithStreams> {
        val filtered = if (group == null) this else filter { it.channel.group == group }
        // The list's display order is the domain's [ChannelSorter] contract (docs/02 §8.1/§8.2), and it
        // is the same comparator the in-memory implementation used — SQL only got the rows cheaply.
        return filtered.sortedWith(compareBy(ChannelSorter.comparator) { it.channel })
    }
}
