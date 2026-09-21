package ilab.iptv.player.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ilab.iptv.player.core.database.entity.MetricEntity
import ilab.iptv.player.core.database.entity.PlayHistoryEntity

/** `SELECT name, AVG(value), COUNT(*)` behind [MetricDao.summary]. */
data class MetricSummaryRow(
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "average") val average: Double?,
    @ColumnInfo(name = "samples") val samples: Int,
)

/**
 * `play_history` — docs/02 §5.1. Append-only, written by the playback path; the read side is the
 * diagnostics page and [StreamDao.healthCounts].
 */
@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(entry: PlayHistoryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<PlayHistoryEntity>): List<Long>

    @Query("SELECT * FROM play_history WHERE channel_id = :channelId ORDER BY started_at DESC LIMIT :limit")
    suspend fun recentForChannel(channelId: Long, limit: Int): List<PlayHistoryEntity>

    @Query("SELECT * FROM play_history ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlayHistoryEntity>

    @Query("SELECT COUNT(*) FROM play_history")
    suspend fun count(): Int

    /** Retention: history older than [beforeMs] is dropped by the maintenance pass. */
    @Query("DELETE FROM play_history WHERE started_at IS NOT NULL AND started_at < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}

/**
 * `metric` — docs/02 §5.1. Cold-path samples (start cost, first-frame timing, PSS), written from the
 * sampling loop and never from the playback thread (docs/02 §4.5 C4).
 */
@Dao
interface MetricDao {

    @Insert
    suspend fun insert(metric: MetricEntity): Long

    @Insert
    suspend fun insertAll(metrics: List<MetricEntity>): List<Long>

    @Query("SELECT * FROM metric WHERE name = :name AND at >= :fromMs ORDER BY at ASC")
    suspend fun since(name: String, fromMs: Long): List<MetricEntity>

    @Query("SELECT name, AVG(value) AS average, COUNT(*) AS samples FROM metric WHERE name = :name AND at >= :fromMs")
    suspend fun summary(name: String, fromMs: Long): MetricSummaryRow?

    @Query("SELECT COUNT(*) FROM metric")
    suspend fun count(): Int

    @Query("DELETE FROM metric WHERE at IS NOT NULL AND at < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
