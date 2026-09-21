package ilab.iptv.player.core.data.di

import android.content.Context
import android.content.res.AssetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.data.repository.InMemoryChannelRepository
import ilab.iptv.player.core.data.repository.InMemoryStreamRepository
import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import ilab.iptv.player.core.data.catalog.ChannelCatalogLoader
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.StreamRepository
import javax.inject.Singleton

/**
 * Wires the P1-2 ports. The bindings are `implementation`-side: feature modules depend on
 * `:core:domain` for the interfaces and never on `:core:data`, so P2-1 can replace the in-memory
 * implementations with Room-backed ones without touching a call site.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAssetManager(@ApplicationContext context: Context): AssetManager = context.assets

    @Provides
    @Singleton
    fun provideChannelRepository(impl: InMemoryChannelRepository): ChannelRepository = impl

    @Provides
    @Singleton
    fun provideCatalogBootstrapper(loader: ChannelCatalogLoader): CatalogBootstrapper = loader

    @Provides
    @Singleton
    fun provideStreamRepository(impl: InMemoryStreamRepository): StreamRepository = impl
}
