package ilab.iptv.player.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.data.repository.RoomChannelRepository
import ilab.iptv.player.core.data.repository.RoomStreamRepository
import ilab.iptv.player.core.data.store.CatalogSink
import ilab.iptv.player.core.data.store.RoomCatalogWriter
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
     * Production binding (god's 2026-09-22 收口 ruling). The earlier comment here explained why Room
     * was *not* bound: P2-1 wrote through [RoomCatalogWriter] while the import wrote through
     * `ChannelStore`, so binding Room would have made the list read one store and the import write the
     * other. That gap is closed — every catalog write now funnels through [CatalogSink], whose only
     * production implementation is [RoomCatalogWriter], so "read Room" and "write Room" are switched
     * together. The in-memory implementations stay in the module (test-only, no Hilt annotation) and
     * are constructed directly by `InMemoryRepositoryTest` / `RoomRepositoryTest`.
     *
     * No DuplicateBindings: this is the *only* place `ChannelRepository`, `StreamRepository` and
     * `CatalogSink` are bound (the `ChannelStore`/`InMemory*` path is no longer a Hilt binding).
     */

    @Provides
    @Singleton
    fun provideCatalogSink(writer: RoomCatalogWriter): CatalogSink = writer

    @Provides
    @Singleton
    fun provideChannelRepository(impl: RoomChannelRepository): ChannelRepository = impl

    @Provides
    @Singleton
    fun provideStreamRepository(impl: RoomStreamRepository): StreamRepository = impl
}
