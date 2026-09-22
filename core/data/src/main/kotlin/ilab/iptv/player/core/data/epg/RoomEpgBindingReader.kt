package ilab.iptv.player.core.data.epg

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.ProgrammeDao
import ilab.iptv.player.core.domain.epg.EpgBindingPort
import ilab.iptv.player.core.model.EpgBindingReport
import ilab.iptv.player.core.model.EpgBindingRow
import ilab.iptv.player.core.model.EpgGridWindow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EpgBindingPort] over Room (BUG-20260922-018's evidence entry).
 *
 * Two reads and one join, all on rows that already exist — no second data channel:
 *
 * 1. every channel (`channelDao.all()`), mapped to the domain shape so `epg_match` and the user's
 *    rename arrive already interpreted;
 * 2. the programme counts per guide id inside the grid window
 *    (`programmeDao.countByChannelInWindow`) — the *same* query and the *same* window
 *    `LoadEpgUseCase` and `RoomEpgRepository.coverage()` use, which is what makes the row count and
 *    the coverage number comparable at one instant.
 *
 * The join is done in memory rather than in SQL on purpose: it is the identity hop
 * (`channel.epg_channel_id → programme.epg_channel_id`) that `RoomEpgRepository` documents as this
 * layer's job, and a `GROUP BY` over ~1k ids is cheaper than a correlated subquery per channel.
 *
 * The window is [EpgGridWindow]'s, taken from the clock, exactly like the stored-guide reader: the
 * table is only *approximately* any window between refreshes, so the report must state the window it
 * counted in (`window(from,to)` is one of the fields the card asks the panel to show).
 */
@Singleton
class RoomEpgBindingReader @Inject constructor(
    private val channelDao: ChannelDao,
    private val programmeDao: ProgrammeDao,
    private val clock: Clock,
) : EpgBindingPort {

    override suspend fun bindingReport(): EpgBindingReport {
        val window = EpgGridWindow.of(clock.nowMs())
        val programmesByEpgId = programmeDao
            .countByChannelInWindow(window.fromMs, window.toMs)
            .associate { row -> row.epgChannelId to row.count }
        val rows = channelDao.all().map { entity ->
            val channel = PersistenceMapper.toDomain(entity)
            val epgChannelId = channel.epgChannelId?.takeIf { it.isNotBlank() }
            EpgBindingRow(
                channelId = channel.id,
                channelName = channel.shownName,
                channelNo = channel.channelNo,
                epgChannelId = epgChannelId,
                matchedBy = channel.epgMatch,
                programmesInWindow = epgChannelId?.let { programmesByEpgId[it] } ?: 0,
                hidden = channel.hidden,
            )
        }
        return EpgBindingReport(window = window, rows = rows)
    }
}
