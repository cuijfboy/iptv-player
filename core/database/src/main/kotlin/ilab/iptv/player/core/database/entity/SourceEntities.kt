package ilab.iptv.player.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `source` — docs/02 §5.1. The playlist/portal subscriptions the user added (P2-6 manages the rows,
 * P2-4 refreshes them). Its id is the source's own key (provider + account or a hash of the URL), so
 * it is a TEXT primary key and never auto-generated.
 */
@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "url") val url: String?,
    @ColumnInfo(name = "kind") val kind: String?,
    @ColumnInfo(name = "enabled") val enabled: Int?,
    @ColumnInfo(name = "last_fetch_at") val lastFetchAt: Long?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
    @ColumnInfo(name = "entry_count") val entryCount: Int?,
)

/**
 * `epg_source` — docs/02 §5.1. Separate from [SourceEntity] because playlists and EPG endpoints are
 * added, enabled and reported on independently (docs/01 F6).
 */
@Entity(tableName = "epg_source")
data class EpgSourceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "url") val url: String?,
    @ColumnInfo(name = "enabled") val enabled: Int?,
    @ColumnInfo(name = "last_fetch_at") val lastFetchAt: Long?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
)
