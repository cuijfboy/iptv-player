package ilab.iptv.player.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `stream` — docs/02 §5.1, column for column.
 *
 * `UNIQUE(channel_id, url_hash)` (`idx_stream_hash`) is the W4 rule that lets one URL serve several
 * channels (same provider, mirror channel numbers) while still de-duplicating a repeated URL inside
 * one channel. `idx_stream_channel(channel_id, score DESC, priority ASC)` is the selection key the
 * fail-over path reads on every button press, so it must be an index and not a sort of the whole
 * table.
 *
 * The foreign key to `channel` cascades: deleting a channel takes its streams with it (that is what
 * a source refresh that drops a channel means). It also means the child column `channel_id` must be
 * indexed — `idx_stream_hash` starts with `channel_id`, which satisfies Room's requirement.
 */
@Entity(
    tableName = "stream",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channel_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["channel_id", "url_hash"], unique = true, name = "idx_stream_hash"),
        Index(value = ["channel_id", "score", "priority"], name = "idx_stream_channel"),
    ],
)
data class StreamEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "url_hash") val urlHash: String,
    @ColumnInfo(name = "user_agent") val userAgent: String?,
    @ColumnInfo(name = "referrer") val referrer: String?,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "quality") val quality: String?,
    @ColumnInfo(name = "vcodec") val vcodec: String?,
    @ColumnInfo(name = "acodec") val acodec: String?,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "score", defaultValue = "0") val score: Int = 0,
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(name = "last_ok_at") val lastOkAt: Long?,
    @ColumnInfo(name = "last_check_at") val lastCheckAt: Long?,
    @ColumnInfo(name = "fail_count", defaultValue = "0") val failCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "disabled", defaultValue = "0") val disabled: Boolean = false,
)
