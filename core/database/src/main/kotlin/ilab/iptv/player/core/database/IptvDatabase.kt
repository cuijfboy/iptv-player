package ilab.iptv.player.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.EpgSourceDao
import ilab.iptv.player.core.database.dao.MetricDao
import ilab.iptv.player.core.database.dao.PlayHistoryDao
import ilab.iptv.player.core.database.dao.ProgrammeDao
import ilab.iptv.player.core.database.dao.SourceDao
import ilab.iptv.player.core.database.dao.StreamDao
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.database.entity.EpgSourceEntity
import ilab.iptv.player.core.database.entity.MetricEntity
import ilab.iptv.player.core.database.entity.PlayHistoryEntity
import ilab.iptv.player.core.database.entity.ProgrammeEntity
import ilab.iptv.player.core.database.entity.SourceEntity
import ilab.iptv.player.core.database.entity.StreamEntity

/**
 * The one Room database (docs/02 §5.1). All seven tables of §5.1 live here:
 * `channel`, `stream`, `programme`, `source`, `epg_source`, `play_history`, `metric`.
 *
 * Two properties the rest of the app depends on:
 * - **`exportSchema = true`** with `room.schemaLocation` (see the `iptv.room` convention plugin) writes
 *   `<module>/schemas/<this class>/<version>.json`. Those files are committed: they are what
 *   `MigrationTestHelper` reads and what a future migration must match, so a schema change without a
 *   new export fails the migration test.
 * - **`VERSION = 2`** (P3-4 频道管理器): v1 is the baseline and v2 adds the two user-owned overlays on
 *   `channel` (`display_name`, `user_group_title`). See [Migrations] for [Migrations.MIGRATION_1_2].
 */
@Database(
    entities = [
        ChannelEntity::class,
        StreamEntity::class,
        ProgrammeEntity::class,
        SourceEntity::class,
        EpgSourceEntity::class,
        PlayHistoryEntity::class,
        MetricEntity::class,
    ],
    version = IptvDatabase.VERSION,
    exportSchema = true,
)
abstract class IptvDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao

    abstract fun streamDao(): StreamDao

    abstract fun programmeDao(): ProgrammeDao

    abstract fun sourceDao(): SourceDao

    abstract fun epgSourceDao(): EpgSourceDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun metricDao(): MetricDao

    companion object {

        /**
         * docs/02 §5.1 / §13: bump this with a migration, never silently. v1 baseline → v2 (P3-4) adds
         * `channel.display_name` and `channel.user_group_title`; see [Migrations.MIGRATION_1_2].
         */
        const val VERSION = 2

        const val NAME = "iptv.db"

        /**
         * The production builder. Deliberately **not** `fallbackToDestructiveMigration`: a schema the
         * app cannot migrate is a build error to fix ([Migrations.ALL] + an exported schema), not user
         * data to throw away (docs/02 §11 "存储写入失败 → 内存态继续可用，下次启动重试").
         *
         * `WRITE_AHEAD_LOGGING` is explicit rather than left to the default: WAL is what keeps a batch
         * upsert from blocking the reader that is painting the channel list (docs/02 §4.5 C4).
         * Main-thread queries stay disabled (the default), so a DAO call on the main thread throws
         * instead of stuttering playback.
         */
        fun build(context: Context, name: String = NAME): IptvDatabase =
            Room.databaseBuilder(context, IptvDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
