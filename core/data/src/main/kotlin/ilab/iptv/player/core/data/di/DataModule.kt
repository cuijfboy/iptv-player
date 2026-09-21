package ilab.iptv.player.core.data.di

import android.content.Context
import android.content.res.AssetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import ilab.iptv.player.core.data.catalog.ChannelCatalogLoader
import javax.inject.Singleton

/**
 * Wires the P1-2 fixture loader.
 *
 * P2-1 moved the `ChannelRepository` / `StreamRepository` bindings to [PersistenceModule] (the
 * Room-backed implementations). The in-memory implementations are still compiled and still tested —
 * they are what the unit tests drive when there is no database — they simply no longer answer the port
 * in the running app.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAssetManager(@ApplicationContext context: Context): AssetManager = context.assets

    @Provides
    @Singleton
    fun provideCatalogBootstrapper(loader: ChannelCatalogLoader): CatalogBootstrapper = loader
}
