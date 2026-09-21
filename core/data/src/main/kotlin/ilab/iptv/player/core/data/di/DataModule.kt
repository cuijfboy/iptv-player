package ilab.iptv.player.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import ilab.iptv.player.core.data.catalog.AssetBundledPlaylist
import ilab.iptv.player.core.data.catalog.BundledPlaylist
import ilab.iptv.player.core.data.catalog.ChannelCatalogLoader
import ilab.iptv.player.core.data.playlist.AndroidPlaylistFileSystem
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.playlist.LocalPlaylistImportRepository
import ilab.iptv.player.core.data.playlist.PlaylistFileSystem
import ilab.iptv.player.core.data.playlist.RememberedPlaylistSource
import ilab.iptv.player.core.data.repository.InMemoryChannelRepository
import ilab.iptv.player.core.data.repository.InMemoryStreamRepository
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
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
    fun provideBundledPlaylist(@ApplicationContext context: Context): BundledPlaylist =
        AssetBundledPlaylist(context.assets)

    /**
     * Local playlist import (P2-6 slice). Both folders live under the app's **own** external files
     * dir, so no storage permission is involved: `.../files/playlists` is where files are dropped
     * (also the `adb push` target in the QA steps) and `.../files/imports` keeps the copy that makes
     * the import survive a restart. `filesDir` is the fallback for a device with no external volume.
     */
    @Provides
    @Singleton
    fun provideImportFolders(@ApplicationContext context: Context): ImportFolders {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return ImportFolders(
            dropFolder = "${root.absolutePath}/playlists",
            storeFolder = "${root.absolutePath}/imports",
        )
    }

    @Provides
    @Singleton
    fun providePlaylistFileSystem(): PlaylistFileSystem = AndroidPlaylistFileSystem()

    @Provides
    @Singleton
    fun provideRememberedPlaylistSource(store: LastImportStore): RememberedPlaylistSource = store

    @Provides
    @Singleton
    fun providePlaylistImportPort(importer: LocalPlaylistImportRepository): PlaylistImportPort = importer

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
