package ilab.iptv.player.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.database.entity.StreamEntity
import kotlinx.coroutines.flow.Flow

/**
 * One channel with its streams, read in a single transaction (docs/02 §5.1 + §5.2). This is the
 * Room-side shape of the domain's `ChannelWithStreams`; `:core:data` maps it, so no feature sees it.
 *
 * `streams` is unordered — the selection order is a domain rule (§4.3's key), applied by the mapper.
 */
data class ChannelWithStreamRows(
    @Embedded val channel: ChannelEntity,
    @Relation(parentColumn = "id", entityColumn = "channel_id") val streams: List<StreamEntity>,
)

/** `SELECT group_key, COUNT(*)` row for [ChannelDao.countByGroupKey]. */
data class GroupCountRow(
    @ColumnInfo(name = "group_key") val groupKey: String,
    @ColumnInfo(name = "count") val count: Int,
)

/** One `(channel, sort_order)` pair of a reorder batch. */
data class ChannelOrder(val channelId: Long, val sortOrder: Int)

/** One `channel` ← EPG match decision: what the matcher resolved and by which tier (docs/02 §6.3). */
data class EpgBinding(val channelId: Long, val epgChannelId: String?, val epgMatch: String)

/**
 * `channel` — docs/02 §5.1.
 *
 * Read queries are written so the indexes in §5.1 are usable:
 * - list reads filter/order on `(group_key, sort_order)` -> `idx_channel_group`;
 * - identity lookups on `(name_key, group_key)` -> `idx_channel_namekey`.
 *
 * The one query with no index behind it is the free-text `query` filter, which SQLite can only serve
 * with a scan (`instr(...)`). With ~1k channels that is a fraction of a frame; the P2-1 record lists
 * it instead of hiding it (a `LIKE '%q%'` predicate cannot use `idx_channel_namekey` either).
 */
@Dao
abstract class ChannelDao {

    @Transaction
    @Query("SELECT * FROM channel ORDER BY group_key ASC, sort_order ASC, id ASC")
    abstract fun observeAll(): Flow<List<ChannelWithStreamRows>>

    /**
     * The browse list's read path: hidden / favourites / group filters are applied in SQL so the row
     * set and the index use stay in the database, and the free-text filter is applied with `instr()`
     * (literal substring, so no wildcard escaping is needed).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM channel
        WHERE (:includeHidden OR hidden = 0)
          AND (:favoritesOnly = 0 OR favorite = 1)
          AND (:groupKey IS NULL OR group_key = :groupKey)
          AND (:query IS NULL OR instr(name_key, :query) > 0 OR instr(group_key, :query) > 0)
        ORDER BY group_key ASC, sort_order ASC, id ASC
        """,
    )
    abstract fun observeFiltered(
        includeHidden: Boolean,
        favoritesOnly: Boolean,
        groupKey: String?,
        query: String?,
    ): Flow<List<ChannelWithStreamRows>>

    @Transaction
    @Query("SELECT * FROM channel WHERE id = :channelId")
    abstract suspend fun getWithStreams(channelId: Long): ChannelWithStreamRows?

    @Query("SELECT * FROM channel WHERE group_key = :groupKey ORDER BY sort_order ASC, id ASC")
    abstract suspend fun byGroup(groupKey: String): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE channel_no = :channelNo ORDER BY id ASC LIMIT 1")
    abstract suspend fun byChannelNo(channelNo: Int): ChannelEntity?

    @Query("SELECT * FROM channel WHERE favorite = 1 ORDER BY group_key ASC, sort_order ASC, id ASC")
    abstract suspend fun favorites(): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE hidden = 1 ORDER BY group_key ASC, sort_order ASC, id ASC")
    abstract suspend fun hidden(): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channel")
    abstract suspend fun count(): Int

    /** Every stored channel id — the replace write ([RoomCatalogWriter]) diffs these against the new set. */
    @Query("SELECT id FROM channel")
    abstract suspend fun allIds(): List<Long>

    @Query("SELECT group_key, COUNT(*) AS count FROM channel GROUP BY group_key")
    abstract suspend fun countByGroupKey(): List<GroupCountRow>

    @Query("SELECT COUNT(*) FROM channel WHERE epg_channel_id IS NOT NULL")
    abstract suspend fun countWithEpg(): Int

    /** Matched channels per group — the `EpgCoverage.byGroup` breakdown (docs/02 §6.3). */
    @Query(
        """
        SELECT group_key, COUNT(*) AS count FROM channel
        WHERE epg_channel_id IS NOT NULL
        GROUP BY group_key
        """,
    )
    abstract suspend fun countWithEpgByGroupKey(): List<GroupCountRow>

    @Query("SELECT id FROM channel WHERE name_key = :nameKey AND group_key = :groupKey LIMIT 1")
    abstract suspend fun findIdByKey(nameKey: String, groupKey: String): Long?

    /** The match pass reads every channel's identity columns once; the binding write uses the ids. */
    @Query("SELECT * FROM channel ORDER BY id ASC")
    abstract suspend fun all(): List<ChannelEntity>

    @Query("SELECT epg_channel_id FROM channel WHERE id = :channelId")
    abstract suspend fun epgChannelIdFor(channelId: Long): String?

    /** Does the row exist at all? Distinct from "it exists but has no EPG binding" (`epg_channel_id` null). */
    @Query("SELECT id FROM channel WHERE id = :channelId")
    abstract suspend fun findIdOrNull(channelId: Long): Long?

    @Query("SELECT * FROM channel WHERE name_key = :nameKey AND group_key = :groupKey LIMIT 1")
    abstract suspend fun findByKey(nameKey: String, groupKey: String): ChannelEntity?

    /** Plain insert: a duplicate `(name_key, group_key)` must throw, not silently merge. */
    @Insert
    abstract suspend fun insert(channel: ChannelEntity): Long

    @Insert
    abstract suspend fun insertAll(channels: List<ChannelEntity>): List<Long>

    @Update
    abstract suspend fun update(channel: ChannelEntity): Int

    /**
     * Upsert keyed on the §5.1 unique index `(name_key, group_key)`, not on the row id: a refresh
     * that sees the same channel again keeps its id — and with it its streams and every column the
     * *user* owns. Running it twice with the same input changes nothing (idempotent).
     *
     * The column split is the important part, because a playlist never carries user intent:
     * - **source-owned** (`name`, `name_key`, `group_key`, `group_title`, `logo`, `tvg_id`): taken from
     *   the incoming row — this is the refresh.
     * - **user-owned** (`favorite`, `hidden`, `sort_order`, `channel_no`, `epg_channel_id`,
     *   `epg_match`): kept from the stored row. docs/01 F5 is explicit that a user edit outranks the
     *   source, so a refresh must not unhide a channel, un-favourite it, re-sort it or drop its EPG
     *   binding. `created_at` is kept too; `updated_at` comes from the incoming row.
     *
     * `channel_no` is the one imperfect case: D12 wants *user edit > source `tvg-chno` > auto*, and
     * §5.1 has a single column with no origin flag, so "keep the stored value, take the incoming one
     * only when the stored one is null" is the closest faithful rule — first writer wins. If a source
     * later renumbers its channels, that change is ignored on purpose; making it possible needs a
     * column (or a user-edit table) that §5.1 does not have. Reported, not patched silently.
     */
    @Transaction
    open suspend fun upsertAll(channels: List<ChannelEntity>): List<Long> {
        if (channels.isEmpty()) return emptyList()
        val ids = ArrayList<Long>(channels.size)
        for (channel in channels) {
            val existing = findByKey(channel.nameKey, channel.groupKey)
            if (existing == null) {
                ids += insert(channel.copy(id = 0))
            } else {
                update(merge(existing, channel))
                ids += existing.id
            }
        }
        return ids
    }

    /** See [upsertAll]: source-owned columns come from [incoming], user-owned ones from [existing]. */
    private fun merge(existing: ChannelEntity, incoming: ChannelEntity): ChannelEntity = incoming.copy(
        id = existing.id,
        createdAt = existing.createdAt,
        favorite = existing.favorite,
        hidden = existing.hidden,
        sortOrder = existing.sortOrder,
        channelNo = existing.channelNo ?: incoming.channelNo,
        epgChannelId = existing.epgChannelId ?: incoming.epgChannelId,
        epgMatch = if (existing.epgMatch == "NONE") incoming.epgMatch else existing.epgMatch,
    )

    @Query("UPDATE channel SET favorite = :favorite, updated_at = :updatedAt WHERE id = :channelId")
    abstract suspend fun setFavorite(channelId: Long, favorite: Boolean, updatedAt: Long): Int

    @Query("UPDATE channel SET hidden = :hidden, updated_at = :updatedAt WHERE id = :channelId")
    abstract suspend fun setHidden(channelId: Long, hidden: Boolean, updatedAt: Long): Int

    /** docs/01 F5 / D12 tier 1: a user-typed number. `null` clears it back to the source value. */
    @Query("UPDATE channel SET channel_no = :channelNo, updated_at = :updatedAt WHERE id = :channelId")
    abstract suspend fun setChannelNo(channelId: Long, channelNo: Int?, updatedAt: Long): Int

    @Query(
        """
        UPDATE channel SET epg_channel_id = :epgChannelId, epg_match = :epgMatch, updated_at = :updatedAt
        WHERE id = :channelId
        """,
    )
    abstract suspend fun setEpgBinding(
        channelId: Long,
        epgChannelId: String?,
        epgMatch: String,
        updatedAt: Long,
    ): Int

    /**
     * One EPG match pass = one transaction. A refresh that fails halfway must not leave a channel
     * pointing at a guide id the programme table has rows for only partly, and a per-row commit loop
     * over 1k channels is 1k transactions (docs/02 §4.5 C4). [setEpgBinding] stays for P3-4's
     * single-channel manual binding.
     */
    @Transaction
    open suspend fun setEpgBindings(bindings: List<EpgBinding>, updatedAt: Long) {
        for (binding in bindings) {
            setEpgBinding(binding.channelId, binding.epgChannelId, binding.epgMatch, updatedAt)
        }
    }

    @Query("UPDATE channel SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :channelId")
    abstract suspend fun setSortOrder(channelId: Long, sortOrder: Int, updatedAt: Long): Int

    /**
     * One reorder = one transaction: the caller rewrites a whole group's `sort_order` (the domain
     * policy computes it) so a half-applied move can never be observed.
     */
    @Transaction
    open suspend fun setSortOrders(orders: List<ChannelOrder>, updatedAt: Long) {
        for (order in orders) setSortOrder(order.channelId, order.sortOrder, updatedAt)
    }

    @Query("DELETE FROM channel WHERE id = :channelId")
    abstract suspend fun deleteById(channelId: Long): Int

    @Query("DELETE FROM channel WHERE id IN (:channelIds)")
    abstract suspend fun deleteByIds(channelIds: List<Long>): Int

    @Query("DELETE FROM channel")
    abstract suspend fun deleteAll(): Int
}
