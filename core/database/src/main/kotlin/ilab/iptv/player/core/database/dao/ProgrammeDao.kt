package ilab.iptv.player.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ilab.iptv.player.core.database.entity.ProgrammeEntity
import kotlinx.coroutines.flow.Flow

/** `SELECT epg_channel_id, COUNT(*)` — the EPG coverage report (docs/02 §4.3 `EpgCoverage`). */
data class ProgrammeChannelCountRow(
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String,
    @ColumnInfo(name = "count") val count: Int,
)

/**
 * `programme` — docs/02 §5.1.
 *
 * Writes are `INSERT OR REPLACE` on `UNIQUE(epg_channel_id, start_ms)` (`idx_prog_slot`), which is
 * the W4 idempotency rule: re-pulling the same XMLTV file updates the slot instead of duplicating it.
 * Reads (grid window, now/next, coverage) all lead with `epg_channel_id` and are served by
 * `idx_prog_lookup`.
 */
@Dao
abstract class ProgrammeDao {

    /**
     * The grid's window query (docs/02 §8.3: at most 64 channels and 24 h). `stop_ms >= :fromMs` is
     * included so a programme that is still running at the left edge of the window is returned.
     */
    @Query(
        """
        SELECT * FROM programme
        WHERE epg_channel_id IN (:epgChannelIds) AND stop_ms >= :fromMs AND start_ms <= :toMs
        ORDER BY epg_channel_id ASC, start_ms ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun window(
        epgChannelIds: List<String>,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): List<ProgrammeEntity>

    @Query(
        """
        SELECT * FROM programme
        WHERE epg_channel_id IN (:epgChannelIds) AND stop_ms >= :fromMs AND start_ms <= :toMs
        ORDER BY epg_channel_id ASC, start_ms ASC
        """,
    )
    abstract fun observeWindow(
        epgChannelIds: List<String>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<ProgrammeEntity>>

    /** The programme covering [atMs] — "now". */
    @Query(
        """
        SELECT * FROM programme
        WHERE epg_channel_id = :epgChannelId AND start_ms <= :atMs AND stop_ms > :atMs
        ORDER BY start_ms DESC LIMIT 1
        """,
    )
    abstract suspend fun now(epgChannelId: String, atMs: Long): ProgrammeEntity?

    /** The next programme starting after [atMs] — "next". */
    @Query(
        """
        SELECT * FROM programme
        WHERE epg_channel_id = :epgChannelId AND start_ms > :atMs
        ORDER BY start_ms ASC LIMIT 1
        """,
    )
    abstract suspend fun next(epgChannelId: String, atMs: Long): ProgrammeEntity?

    @Query("SELECT COUNT(*) FROM programme WHERE epg_channel_id = :epgChannelId")
    abstract suspend fun countForChannel(epgChannelId: String): Int

    @Query("SELECT COUNT(*) FROM programme")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT epg_channel_id) FROM programme")
    abstract suspend fun channelCount(): Int

    @Query("SELECT epg_channel_id, COUNT(*) AS count FROM programme GROUP BY epg_channel_id")
    abstract suspend fun countByChannel(): List<ProgrammeChannelCountRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun replaceAll(programmes: List<ProgrammeEntity>)

    /**
     * One EPG batch in one transaction (docs/02 §4.5 C4: batch writes are transactional and never
     * share a thread with playback). A mid-batch failure rolls the batch back instead of leaving half
     * a channel's schedule behind.
     */
    @Transaction
    open suspend fun replaceBatch(programmes: List<ProgrammeEntity>) {
        if (programmes.isEmpty()) return
        replaceAll(programmes)
    }

    @Query("DELETE FROM programme WHERE epg_channel_id IN (:epgChannelIds) AND stop_ms < :fromMs")
    abstract suspend fun deleteEndedBefore(epgChannelIds: List<String>, fromMs: Long): Int

    @Query("DELETE FROM programme WHERE epg_channel_id IN (:epgChannelIds) AND start_ms > :toMs")
    abstract suspend fun deleteStartingAfter(epgChannelIds: List<String>, toMs: Long): Int

    @Query("DELETE FROM programme WHERE stop_ms < :fromMs")
    abstract suspend fun deleteAllEndedBefore(fromMs: Long): Int

    @Query("DELETE FROM programme WHERE start_ms > :toMs")
    abstract suspend fun deleteAllStartingAfter(toMs: Long): Int

    /**
     * §5.1's pruning rule: keep the `[fromMs, toMs]` window, drop what is outside it, per
     * `epg_channel_id` batch. Both deletes run in one transaction, so a prune never leaves a channel
     * with a hole in the middle: either the whole batch is inside the window or none of it is trimmed.
     */
    @Transaction
    open suspend fun pruneWindow(epgChannelIds: List<String>, fromMs: Long, toMs: Long): Int {
        if (epgChannelIds.isEmpty()) return 0
        return deleteEndedBefore(epgChannelIds, fromMs) + deleteStartingAfter(epgChannelIds, toMs)
    }

    @Query("DELETE FROM programme")
    abstract suspend fun deleteAll(): Int
}
