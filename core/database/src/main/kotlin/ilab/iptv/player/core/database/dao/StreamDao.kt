package ilab.iptv.player.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import ilab.iptv.player.core.database.entity.StreamEntity

/** `SELECT channel_id, COUNT(*)` row, used to fill `Channel.streamCount` without a join. */
data class ChannelStreamCountRow(
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "count") val count: Int,
)

/**
 * `play_history` aggregate behind [StreamDao.healthCounts] (see its KDoc for the definition).
 *
 * `failures` is nullable because SQLite's `SUM()` over an empty set is `NULL`, not `0` — a stream with
 * no history yet has 0 attempts and "no failure count", and the caller decides (the repository maps it
 * to 0). Typing it non-null would silently read a wrong value, which is what the DAO test pins down.
 */
data class StreamHealthRow(
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "failures") val failures: Int?,
)

/**
 * `stream` — docs/02 §5.1.
 *
 * The read the fail-over path makes on every zap (`candidates`) is covered by
 * `idx_stream_channel(channel_id, score, priority)`; the upsert identity is
 * `UNIQUE(channel_id, url_hash)` via `idx_stream_hash`. Both are §5.1's indexes, so no query here
 * needs a table scan.
 */
@Dao
abstract class StreamDao {

    /** Candidates for one channel, already in the §4.3 selection order (score down, priority up, id up). */
    @Query(
        """
        SELECT * FROM stream
        WHERE channel_id = :channelId AND disabled = 0
        ORDER BY score DESC, priority ASC, id ASC
        """,
    )
    abstract suspend fun candidates(channelId: Long): List<StreamEntity>

    @Query("SELECT * FROM stream WHERE channel_id = :channelId ORDER BY score DESC, priority ASC, id ASC")
    abstract suspend fun allForChannel(channelId: Long): List<StreamEntity>

    @Query("SELECT * FROM stream WHERE id = :streamId")
    abstract suspend fun getById(streamId: Long): StreamEntity?

    @Query("SELECT COUNT(*) FROM stream WHERE channel_id = :channelId")
    abstract suspend fun countForChannel(channelId: Long): Int

    @Query("SELECT channel_id, COUNT(*) AS count FROM stream GROUP BY channel_id")
    abstract suspend fun countByChannel(): List<ChannelStreamCountRow>

    @Query("SELECT id FROM stream WHERE channel_id = :channelId AND url_hash = :urlHash LIMIT 1")
    abstract suspend fun findIdByChannelAndHash(channelId: Long, urlHash: String): Long?

    /** Plain insert: a duplicate `(channel_id, url_hash)` must throw, not silently merge. */
    @Insert
    abstract suspend fun insert(stream: StreamEntity): Long

    /** Plain insert of a batch; one duplicate aborts the whole statement (used by the index test). */
    @Insert
    abstract suspend fun insertAll(streams: List<StreamEntity>): List<Long>

    @Update
    abstract suspend fun update(stream: StreamEntity): Int

    /**
     * Upsert keyed on `(channel_id, url_hash)` so a repeated URL keeps its row id — and with it the
     * health columns and the `play_history` rows that reference the stream. Idempotent: the same batch
     * twice leaves exactly one row per key.
     */
    @Transaction
    open suspend fun upsertAll(streams: List<StreamEntity>): Int {
        if (streams.isEmpty()) return 0
        var written = 0
        for (stream in streams) {
            val existing = findIdByChannelAndHash(stream.channelId, stream.urlHash)
            if (existing == null) {
                insert(stream.copy(id = 0))
            } else {
                update(stream.copy(id = existing))
            }
            written++
        }
        return written
    }

    /**
     * One probe result, as a single statement (no read-modify-write, so two concurrent probes cannot
     * lose each other's counters): on success the OK stamp moves and `fail_count` resets; on failure
     * the stamp stays and `fail_count` grows.
     */
    @Query(
        """
        UPDATE stream SET
            last_ok_at = CASE WHEN :ok THEN :atMs ELSE last_ok_at END,
            last_check_at = :atMs,
            fail_count = CASE WHEN :ok THEN 0 ELSE fail_count + 1 END,
            last_error = CASE WHEN :ok THEN NULL ELSE :lastError END
        WHERE id = :streamId
        """,
    )
    abstract suspend fun recordOutcome(streamId: Long, ok: Boolean, atMs: Long, lastError: String?): Int

    /**
     * "Re-probe me": clears the check stamp of every stream whose stamp is older than [beforeMs] and
     * returns how many changed. A never-checked stream (`last_check_at IS NULL`) is not stale, it is
     * unknown, so it is left alone.
     */
    @Query("UPDATE stream SET last_check_at = NULL WHERE last_check_at IS NOT NULL AND last_check_at < :beforeMs")
    abstract suspend fun markStale(beforeMs: Long): Int

    /**
     * docs/02 §4.3 `StreamRepository.health`, derived from durable rows instead of an in-process map:
     * `attempts` and `failures` come from this stream's `play_history` rows, `lastOkAtMs` from the
     * stream's own OK stamp and `consecutiveFails` from `fail_count`.
     *
     * Known approximation (reported, not hidden): a probe-only failure (P2-4's validation pipeline)
     * bumps `fail_count` but writes no `play_history` row, so it shows up in `consecutiveFails` and not
     * in `attempts`. §5.1 has no health table, and inventing one was not this card's call.
     */
    @Query(
        """
        SELECT COUNT(*) AS attempts,
               SUM(CASE WHEN result = 'OK' THEN 0 ELSE 1 END) AS failures
        FROM play_history WHERE stream_id = :streamId
        """,
    )
    abstract suspend fun healthCounts(streamId: Long): StreamHealthRow?

    @Query("SELECT last_ok_at FROM stream WHERE id = :streamId")
    abstract suspend fun lastOkAt(streamId: Long): Long?

    @Query("SELECT fail_count FROM stream WHERE id = :streamId")
    abstract suspend fun consecutiveFails(streamId: Long): Int?

    @Query("DELETE FROM stream WHERE id IN (:streamIds)")
    abstract suspend fun deleteByIds(streamIds: List<Long>): Int

    @Query("DELETE FROM stream WHERE channel_id = :channelId")
    abstract suspend fun deleteByChannel(channelId: Long): Int

    @Query("DELETE FROM stream")
    abstract suspend fun deleteAll(): Int
}
