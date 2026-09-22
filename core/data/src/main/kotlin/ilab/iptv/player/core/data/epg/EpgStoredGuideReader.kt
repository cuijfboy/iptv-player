package ilab.iptv.player.core.data.epg

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.model.EpgGridWindow
import ilab.iptv.player.core.model.EpgStoredGuide
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is what we have stored worth calling fresh?" — the second input of the P3-6 trigger gate
 * (BUG-20260922-016).
 *
 * `EpgSourceStatusReader` answers *when* the last fetch happened; this answers *whether it produced
 * anything a viewer can see*. Both are needed, because a cold start that ran before the channel table
 * was seeded fetched four guides successfully and bound nothing — a fresh timestamp over a blank TV.
 *
 * Same shape and same reason as `EpgSourceStatusReader`: `:core:data` is the only module that owns the
 * `channel`/`programme` tables, so the read is a port here and a Room implementation next to it, and
 * the caller (`:app`'s scheduler and coordinator) never sees Room.
 *
 * The window is [EpgGridWindow]'s, not the retention window: "has programmes" has to mean the same
 * thing here as it does on the panel and in the grid (BUG-20260922-018).
 */
interface EpgStoredGuideReader {

    suspend fun read(): EpgStoredGuide
}

@Singleton
class RoomEpgStoredGuideReader @Inject constructor(
    private val channelDao: ChannelDao,
    private val clock: Clock,
) : EpgStoredGuideReader {

    /**
     * Three counts, no group breakdown: the gate runs on the cold-start path, so it asks only what it
     * decides with. An empty table is reported honestly (`channels = 0`), which is what makes the gate
     * wait for the catalogue instead of running a fetch nothing can be bound from.
     */
    override suspend fun read(): EpgStoredGuide {
        val window = EpgGridWindow.of(clock.nowMs())
        return EpgStoredGuide(
            channels = channelDao.count(),
            matched = channelDao.countWithEpg(),
            programmed = channelDao.countWithEpgProgrammes(window.fromMs, window.toMs),
        )
    }
}
