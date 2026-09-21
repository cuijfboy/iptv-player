package ilab.iptv.player.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.data.repository.RoomChannelRepository
import ilab.iptv.player.core.data.repository.RoomStreamRepository
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.EpgSourceDao
import ilab.iptv.player.core.database.dao.MetricDao
import ilab.iptv.player.core.database.dao.PlayHistoryDao
import ilab.iptv.player.core.database.dao.ProgrammeDao
import ilab.iptv.player.core.database.dao.SourceDao
import ilab.iptv.player.core.database.dao.StreamDao
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.StreamRepository
import javax.inject.Singleton

/**
 * Room wiring, and the one place where "which implementation answers the port" is decided.
 *
 * **Why this module lives in `:core:data` and not in `:core:database`.** A Hilt module is aggregated
 * into the app's component through the *compile* classpath, and Dagger's generated component names the
 * types of every binding it can reach. `:app` may not declare `:core:database` (docs/02 §3.2 rule 3)
 * and `:core:data` may not expose it with `api` (rule 5), so a `@Module` in `:core:database` would
 * either be invisible to the component or force a dependency the guard rejects. Keeping the module
 * here — next to the repositories it wires, exactly like the P1-2 `DataModule` — keeps both rules and
 * gets the bindings into the graph. The P2-1 record states this explicitly.
 */
@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IptvDatabase = IptvDatabase.build(context)

    @Provides
    fun provideChannelDao(database: IptvDatabase): ChannelDao = database.channelDao()

    @Provides
    fun provideStreamDao(database: IptvDatabase): StreamDao = database.streamDao()

    @Provides
    fun provideProgrammeDao(database: IptvDatabase): ProgrammeDao = database.programmeDao()

    @Provides
    fun provideSourceDao(database: IptvDatabase): SourceDao = database.sourceDao()

    @Provides
    fun provideEpgSourceDao(database: IptvDatabase): EpgSourceDao = database.epgSourceDao()

    @Provides
    fun providePlayHistoryDao(database: IptvDatabase): PlayHistoryDao = database.playHistoryDao()

    @Provides
    fun provideMetricDao(database: IptvDatabase): MetricDao = database.metricDao()

    /*
     * god 集成裁决（2026-09-22）：P2-1 的 Room 仓储**暂不绑定**为生产实现。
     *
     * 原因：P2-1 与 TESTABLE-1 是并行工作——Room 这条路的写入端是 [RoomCatalogWriter]（由
     * RoomCatalogSeeder 使用），而导入/加载这条路的写入端是 ChannelCatalog → ChannelStore（内存）。
     * 若把 Room 绑成生产实现，列表会读 Room、导入却写内存，出现「导入后列表没变化」的错配
     * （且 cold-start 语义与 imported catalog 不一致）。两条实现同时绑定还会触发 Dagger
     * DuplicateBindings。
     *
     * 因此：生产先维持 P1-2 的内存实现（行为与 P1 一致），Room 相关代码与测试保留但不接线；
     * 由后续集成任务引入统一的写入接缝（CatalogSink），再把 Room 切成生产实现并补
     * 「导入 → 落 Room → 冷启动仍在」的集成测试。
     */
}
