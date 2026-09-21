package ilab.iptv.player.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `programme` — docs/02 §5.1, column for column.
 *
 * `UNIQUE(epg_channel_id, start_ms)` (`idx_prog_slot`) is the W4 idempotency rule: pulling the same
 * XMLTV file twice must not double the rows, so writes are `INSERT OR REPLACE` on that key. The
 * non-unique `idx_prog_lookup(epg_channel_id, start_ms)` is the one the grid's time-window query
 * uses (`WHERE epg_channel_id IN (…) AND start_ms <= :to AND stop_ms >= :from`); keeping both is
 * deliberate — the unique index alone would also do, but §5.1 names both and the plain index is what
 * a `EXPLAIN QUERY PLAN` reports for range scans.
 *
 * `desc` is a SQL keyword; Room quotes every identifier it emits, so the column name from §5.1 is
 * safe to keep verbatim.
 */
@Entity(
    tableName = "programme",
    indices = [
        Index(value = ["epg_channel_id", "start_ms"], name = "idx_prog_lookup"),
        Index(value = ["epg_channel_id", "start_ms"], unique = true, name = "idx_prog_slot"),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "stop_ms") val stopMs: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "desc") val desc: String?,
    @ColumnInfo(name = "category") val category: String?,
)
