package ilab.iptv.player.core.domain.repository

import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.NowNext
import ilab.iptv.player.core.model.Programme
import kotlinx.coroutines.flow.Flow

/**
 * EPG port (docs/02 §4.3, frozen shape). `:core:data` implements it over Room; a feature only ever
 * sees this interface, so the player screen can ask for now/next without seeing `:core:database`
 * (docs/02 §3.2 rule 2 — `:feature:*` may not declare `:core:epg` either).
 *
 * Two things worth spelling out because the signatures do not say them:
 *
 * - **`nowNext` is keyed on the business channel id, not on `epg_channel_id`.** The info bar knows
 *   which channel it is playing, not which XMLTV channel that maps to; resolving `channel.id →
 *   channel.epg_channel_id → programme` is the implementation's job.
 * - **`replaceAll` is per EPG channel and idempotent.** §5.1's `UNIQUE(epg_channel_id, start_ms)` plus
 *   `INSERT OR REPLACE` means re-pulling the same XMLTV file updates the same slots instead of
 *   duplicating them. §4.3 spells the parameter `channelKey`; here it is named `epgChannelId` because
 *   that is the column it is: the programme table's key is the *EPG* channel id.
 */
interface EpgRepository {

    /** The grid window (≤ [EpgWindowQuery.limit] channels, docs/02 §8.3). */
    fun observeWindow(query: EpgWindowQuery): Flow<List<Programme>>

    /** The programme covering [atMs] and the one after it, or nulls when the channel has no EPG. */
    suspend fun nowNext(channelId: Long, atMs: Long): NowNext?

    /** Channels with EPG data / all channels, plus the per-group breakdown (docs/02 §6.3). */
    suspend fun coverage(): EpgCoverage

    /** One EPG channel's rows, upserted on `(epg_channel_id, start_ms)`. Returns the rows written. */
    suspend fun replaceAll(epgChannelId: String, programmes: List<Programme>): Int

    /**
     * §5.1's retention window `[keepFromMs, keepToMs]`: drops what ended before and what starts after.
     * Returns how many rows were removed.
     */
    suspend fun prune(keepFromMs: Long, keepToMs: Long): Int
}
