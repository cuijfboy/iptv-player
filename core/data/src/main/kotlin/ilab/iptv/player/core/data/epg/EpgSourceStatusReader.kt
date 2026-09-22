package ilab.iptv.player.core.data.epg

import ilab.iptv.player.core.database.dao.EpgSourceDao
import ilab.iptv.player.core.model.EpgSourceStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "How fresh is the stored guide?" (P3-6).
 *
 * It exists because two different layers need the answer and neither may read the table itself:
 * `:app`'s trigger gate (is a wake-up worth it?) and `:feature:settings`' panel (when did EPG last
 * run?). `:core:data` is the only module that owns the `epg_source` table (§3.2), so the read is a
 * one-method port here and a Room implementation next to it.
 *
 * It is a *reader* and nothing else: [ilab.iptv.player.core.data.epg.LoadEpgUseCase] writes those
 * columns at the end of every source attempt, and the P2-6 source manager owns the enable switches.
 */
interface EpgSourceStatusReader {

    suspend fun read(): EpgSourceStatus
}

@Singleton
class RoomEpgSourceStatusReader @Inject constructor(
    private val sourceDao: EpgSourceDao,
) : EpgSourceStatusReader {

    /**
     * The rows go in as-is; the newest fetch and its result are the two facts the callers ask for.
     * An empty table (nothing has run yet, not even the first seeding) is reported honestly as
     * "no sources, never fetched" rather than as a fresh state — [EpgSourceStatus.lastFetchAtMs] being
     * null is what makes the trigger run.
     */
    override suspend fun read(): EpgSourceStatus {
        val rows = sourceDao.all()
        val newest = rows.mapNotNull { it.lastFetchAt }.maxOrNull()
        return EpgSourceStatus(
            sources = rows.size,
            enabledSources = rows.count { it.enabled != 0 },
            lastFetchAtMs = newest,
            lastResult = rows.firstOrNull { it.lastFetchAt == newest && newest != null }?.lastResult,
        )
    }
}
