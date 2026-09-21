package ilab.iptv.player.core.data.epg

import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.ProgrammeDao
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.NowNext
import ilab.iptv.player.core.model.Programme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EpgRepository] over Room (docs/02 §4.3, implemented by `:core:data`).
 *
 * The one job this class has that the DAOs cannot do is the **identity hop**: every read path outside
 * this module is keyed on the business channel id (the player knows channel 7, not `CCTV1.cn`), while
 * `programme` is keyed on `epg_channel_id` (§5.1). The hop is `channel.id → channel.epg_channel_id →
 * programme`, and it is made here so no caller ever sees two identifiers for the same thing.
 *
 * Writes go through [ProgrammeDao.replaceBatch] (batch = one transaction, §4.5 C4) whose
 * `INSERT OR REPLACE` on `UNIQUE(epg_channel_id, start_ms)` is §5.1's W4 idempotency rule.
 */
@Singleton
class RoomEpgRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val programmeDao: ProgrammeDao,
) : EpgRepository {

    override fun observeWindow(query: EpgWindowQuery): Flow<List<Programme>> = flow {
        // §8.3 caps a grid page at 64 channels: the cap is applied to the *channels*, so the SQL IN
        // list stays small and `idx_prog_lookup` serves the window range per channel.
        val epgIds = query.channelIds
            .take(query.limit.coerceAtLeast(1))
            .mapNotNull { channelDao.epgChannelIdFor(it)?.takeIf { id -> id.isNotBlank() } }
            .distinct()
        if (epgIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            programmeDao.observeWindow(epgIds, query.fromMs, query.toMs)
                .map { rows -> rows.map(PersistenceMapper::toDomain) },
        )
    }

    /**
     * A channel with no binding still answers — with two nulls. "This channel has no EPG" is a fact
     * the info bar renders (it shows the channel name alone); `null` is reserved for "there is no such
     * channel", which is a different thing and must not be collapsed into it.
     */
    override suspend fun nowNext(channelId: Long, atMs: Long): NowNext? {
        val epgId = channelDao.epgChannelIdFor(channelId)?.takeIf { it.isNotBlank() }
        if (epgId == null) {
            // `epg_channel_id` being null means two different things — "this channel has no EPG" and
            // "there is no such channel" — and the caller needs to tell them apart. One extra id read
            // is cheaper than a JOIN, and it keeps the two answers honest.
            return if (channelDao.findIdOrNull(channelId) == null) {
                null
            } else {
                NowNext(now = null, next = null)
            }
        }
        val now = programmeDao.now(epgId, atMs)
        val next = programmeDao.next(epgId, atMs)
        return NowNext(
            now = now?.let(PersistenceMapper::toDomain),
            next = next?.let(PersistenceMapper::toDomain),
        )
    }

    /**
     * §6.3's coverage: channels that carry a guide id over all channels, of which the table has rows
     * or not — the *binding* is what coverage measures, since that is what the matcher achieved.
     */
    override suspend fun coverage(): EpgCoverage {
        val byGroup = channelDao.countWithEpgByGroupKey()
            .groupBy({ ChannelGrouping.classify(it.groupKey) }, { it.count })
            .mapValues { (_, counts) -> counts.sum() }
        return EpgCoverage(
            matched = channelDao.countWithEpg(),
            total = channelDao.count(),
            byGroup = byGroup,
        )
    }

    override suspend fun replaceAll(epgChannelId: String, programmes: List<Programme>): Int {
        if (programmes.isEmpty()) return 0
        val entities = programmes
            .filter { it.epgChannelId == epgChannelId }
            .map(PersistenceMapper::toEntity)
        if (entities.isEmpty()) return 0
        programmeDao.replaceBatch(entities)
        return entities.size
    }

    /**
     * §5.1's retention trim. It is global rather than per-channel on purpose: the window
     * `[now-6h, now+48h]` is a property of *time*, not of a channel, so a channel that drops out of a
     * guide would otherwise keep stale rows forever. `deleteAll…` also avoids an `IN (…)` list of 1k
     * ids on every refresh.
     */
    override suspend fun prune(keepFromMs: Long, keepToMs: Long): Int =
        programmeDao.deleteAllEndedBefore(keepFromMs) + programmeDao.deleteAllStartingAfter(keepToMs)
}
