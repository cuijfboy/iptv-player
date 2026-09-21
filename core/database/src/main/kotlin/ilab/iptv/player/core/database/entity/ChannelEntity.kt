package ilab.iptv.player.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `channel` — docs/02 §5.1, column for column.
 *
 * Two behaviours the docs fix and this entity must not lose:
 * - **`UNIQUE(name_key, group_key)`** (`idx_channel_namekey`): the same channel name in two different
 *   source groups is two rows, on purpose (W4). The unique index — not the autoincrement id — is the
 *   identity an upsert keys on.
 * - **`idx_channel_group(group_key, sort_order)`**: the browse list reads one section at a time in
 *   display order, so this index covers both the filter and the `ORDER BY`.
 *
 * Every column is a primitive on purpose (§5.2): the enum columns (`epg_match`) are stored as their
 * enum *names* and converted in `:core:data`'s mapper, so no domain type leaks into Room.
 */
@Entity(
    tableName = "channel",
    indices = [
        Index(value = ["group_key", "sort_order"], name = "idx_channel_group"),
        Index(value = ["name_key", "group_key"], unique = true, name = "idx_channel_namekey"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "tvg_id") val tvgId: String?,
    @ColumnInfo(name = "group_key") val groupKey: String,
    @ColumnInfo(name = "group_title") val groupTitle: String?,
    @ColumnInfo(name = "logo") val logo: String?,
    @ColumnInfo(name = "channel_no") val channelNo: Int?,
    @ColumnInfo(name = "favorite", defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(name = "hidden", defaultValue = "0") val hidden: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String?,
    @ColumnInfo(name = "epg_match", defaultValue = "NONE") val epgMatch: String = "NONE",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
