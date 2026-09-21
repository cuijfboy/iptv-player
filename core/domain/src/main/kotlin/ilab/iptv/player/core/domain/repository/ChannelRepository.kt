package ilab.iptv.player.core.domain.repository

import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.ChannelWithStreams
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth
import ilab.iptv.player.core.model.StreamOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Channel port (docs/02 §4.3, frozen shape). `:core:data` implements it; `:feature:*` only ever sees
 * this interface, so the presentation layer cannot reach Room or the parser (docs/02 §3.2 rule 2).
 *
 * P1-2 ships the **in-memory** implementation (no Room, that is P2-1). The signature is the frozen
 * one on purpose: swapping in the Room-backed implementation later must not change a single call
 * site.
 */
interface ChannelRepository {

    /** Every channel matching [filter], with its streams already ordered (docs/02 §4.3 selection rules). */
    fun observe(filter: ChannelFilter): Flow<List<ChannelWithStreams>>

    suspend fun get(channelId: Long): ChannelWithStreams?

    suspend fun setFavorite(channelId: Long, favorite: Boolean)

    suspend fun setHidden(channelId: Long, hidden: Boolean)

    /** Moves a channel to [newIndex] inside its own `group_key` section. */
    suspend fun reorder(channelId: Long, newIndex: Int)

    /** docs/01 F5: a number set here outranks the source's `tvg-chno` (D12 tier 1). */
    suspend fun setChannelNo(channelId: Long, channelNo: Int?)

    suspend fun setEpgBinding(channelId: Long, epgChannelId: String?, match: EpgMatchType)

    suspend fun countByGroup(): Map<ChannelGroup, Int>
}

/**
 * Stream port (docs/02 §4.3, frozen shape). Split from [ChannelRepository] because the two have
 * different writers: the browse list only reads channels, the refresh pipeline owns streams.
 */
interface StreamRepository {

    /** Candidates for one channel, already ordered by the §4.3 key: score desc → priority asc → id asc. */
    suspend fun candidates(channelId: Long): List<Stream>

    suspend fun upsertAll(streams: List<Stream>)

    suspend fun recordOutcome(streamId: Long, outcome: StreamOutcome)

    suspend fun health(streamId: Long): StreamHealth

    /** Drops the "checked at" stamp of streams last checked before [beforeMs]; returns how many. */
    suspend fun markStale(beforeMs: Long): Int
}
