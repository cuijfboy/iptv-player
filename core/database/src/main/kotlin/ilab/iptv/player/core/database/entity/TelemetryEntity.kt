package ilab.iptv.player.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `play_history` — docs/02 §5.1. One row per watch attempt: the fail-over path writes it so the
 * diagnostics page and the scorer can see what actually happened (docs/03 §3.3 `PLAY_*` events are
 * the live view, this table is the durable one).
 *
 * §5.1 gives this table **no** index. The diagnostics read ("last N attempts for one channel") and
 * the health aggregate therefore scan; the table only grows by one row per watch attempt and the
 * P2-1 record lists that as a finding rather than silently adding an index §5.1 does not have.
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: Long?,
    @ColumnInfo(name = "stream_id") val streamId: Long?,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "start_cost_ms") val startCostMs: Long?,
    /** `StreamOutcome`/`AppError` name, or `OK`; see docs/02 §4.6. */
    @ColumnInfo(name = "result") val result: String?,
    @ColumnInfo(name = "failover_count") val failoverCount: Int?,
)

/**
 * `metric` — docs/02 §5.1 / §4.3 `MetricsRepository`. Cold-path telemetry (start cost, first-frame
 * timing, PSS sample) written by the sampling loop, never by the playback thread (docs/02 §4.5 C4).
 *
 * §5.1 gives this table **no** index either, so the summary query (`WHERE name = :name AND at >=
 * :from`) scans. Same treatment as `play_history`: reported as a finding, not patched silently.
 */
@Entity(tableName = "metric")
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "value") val value: Double?,
    @ColumnInfo(name = "channel_id") val channelId: Long?,
    @ColumnInfo(name = "stream_id") val streamId: Long?,
    @ColumnInfo(name = "at") val at: Long?,
)
