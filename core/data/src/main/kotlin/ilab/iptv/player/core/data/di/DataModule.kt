package ilab.iptv.player.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.data.catalog.AssetBundledPlaylist
import ilab.iptv.player.core.data.catalog.BundledPlaylist
import ilab.iptv.player.core.data.playlist.AndroidPlaylistFileSystem
import ilab.iptv.player.core.data.playlist.AndroidUriPermissionStore
import ilab.iptv.player.core.data.playlist.ContentResolverDocumentReader
import ilab.iptv.player.core.data.playlist.DocumentReader
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.playlist.LocalPlaylistImportRepository
import ilab.iptv.player.core.data.playlist.PlaylistFileSystem
import ilab.iptv.player.core.data.playlist.RememberedPlaylistSource
import ilab.iptv.player.core.data.playlist.UriPermissionStore
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import javax.inject.Singleton

/**
 * Wires the sources that are not storage: the bundled fixture, the local-import file system, and the
 * import port. The bindings are `implementation`-side: feature modules depend on `:core:domain` for
 * the interfaces and never on `:core:data`.
 *
 * The storage ports (`ChannelRepository`, `StreamRepository`) and the catalog write seam
 * (`CatalogSink`) moved to [PersistenceModule], which is the one place "which implementation answers
 * the port" is decided. Keeping them here as in-memory bindings alongside the Room ones is what
 * produced the DuplicateBindings and the read-Room/write-memory mismatch god ruled on 2026-09-22.
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

    /**
     * SAF (P2-6 item 3): reading a document the user picked, and holding the persisted read grant
     * for it. Both are thin `ContentResolver` adapters; the importer takes them as seams so every
     * failure branch (permission refused, document gone between pick and read) is a unit test.
     */
    @Provides
    @Singleton
    fun provideDocumentReader(@ApplicationContext context: Context): DocumentReader =
        ContentResolverDocumentReader(context.contentResolver)

    @Provides
    @Singleton
    fun provideUriPermissionStore(@ApplicationContext context: Context): UriPermissionStore =
        AndroidUriPermissionStore(context.contentResolver)
}
