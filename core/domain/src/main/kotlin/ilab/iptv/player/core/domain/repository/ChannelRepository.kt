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

    /**
     * P3-4 rename: the user's display name, or null to fall back to the source name.
     *
     * Writes the **user-owned overlay** (`channel.display_name`), never `name` / `name_key`: the
     * source name stays the identity the refresh upserts on and the EPG matcher compares against, so
     * a rename cannot re-identify the channel or quietly break its EPG binding.
     */
    suspend fun rename(channelId: Long, displayName: String?)

    /**
     * P3-4 move-group: the user's replacement group title, or null to fall back to the source group.
     *
     * Writes `channel.user_group_title`; the effective group (and its key) is derived from it by
     * [ilab.iptv.player.core.domain.channel.ChannelGrouping]. The stored `group_key` — half of
     * `UNIQUE(name_key, group_key)` — is deliberately left alone.
     */
    suspend fun setUserGroup(channelId: Long, groupTitle: String?)

    /**
     * P3-4 batch delete: removes the channels (and, by the §5.1 cascade, their streams). Returns how
     * many rows were removed. Hard delete on purpose — see the P3-4 record for the reasoning and for
     * the one consequence (a later refresh re-adds a channel the playlist still carries).
     */
    suspend fun deleteChannels(channelIds: List<Long>): Int

    /**
     * P3-4 undo of a batch delete: re-inserts the snapshots taken before the delete, ids included, so
     * the restored rows keep their streams and their user-owned columns.
     */
    suspend fun restoreChannels(items: List<ChannelWithStreams>): Int

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
