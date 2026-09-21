package ilab.iptv.player.core.data.catalog

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.playlist.FakeDocumentReader
import ilab.iptv.player.core.data.playlist.FakePlaylistFileSystem
import ilab.iptv.player.core.data.playlist.FakeUriPermissionStore
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.playlist.LocalPlaylistImportRepository
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.refresh.FakeSessionIds
import ilab.iptv.player.core.data.refresh.RecordingLogger
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The launch report has to describe the catalog the app is **showing**, not the one the loader
 * happened to read at boot (dev-a-13 §6 ⑧: `ChannelCatalogLoader.report()` kept reporting the
 * fixture after an import replaced the store).
 *
 * Both paths publish through `ChannelCatalog.commit`, which is why the report can live next to the
 * store instead of being patched by every caller — and why this test drives the *real* importer
 * instead of calling `commit` by hand: the promise is about the user-visible flow.
 */
class CatalogReportAfterImportTest {

    private val folders = ImportFolders(dropFolder = "/app/files/playlists", storeFolder = "/app/files/imports")
    private val files = FakePlaylistFileSystem()
    private val store = ChannelStore()
    private val catalog = ChannelCatalog(store, FakeClock())
    private val lastImport = LastImportStore(files, folders)
    private val logger = RecordingLogger()
    private val loader = ChannelCatalogLoader(
        dispatchers = TestDispatcherProvider(),
        bundled = fixture(),
        remembered = lastImport,
        catalog = catalog,
        logger = logger,
    )
    private val importer = LocalPlaylistImportRepository(
        files = files,
        folders = folders,
        lastImport = lastImport,
        catalog = catalog,
        logger = logger,
        clock = FakeClock(),
        sessionIds = FakeSessionIds(),
        limits = PipelineLimits(),
        dispatchers = TestDispatcherProvider(),
        documents = FakeDocumentReader(),
        permissions = FakeUriPermissionStore(),
    )

    @Test
    fun `the launch report follows an import instead of staying on the boot numbers`(): Unit = runBlocking {
        val boot = loader.ensureLoaded()
        assertThat(boot?.sourceId).isEqualTo("bundled-fixture")
        assertThat(loader.report()?.channels).isEqualTo(1)
        assertThat(loader.report()?.streams).isEqualTo(1)

        files.put("${folders.dropFolder}/three.m3u", IMPORTED)
        val result = importer.import(
            ImportCandidate("${folders.dropFolder}/three.m3u", "three.m3u", IMPORTSIZE, 0L),
        )
        assertThat(result).isInstanceOf(ImportResult.Done::class.java)

        val afterImport = loader.report()
        assertThat(afterImport?.sourceId).isEqualTo("local:three.m3u")
        assertThat(afterImport?.channels).isEqualTo(3)
        assertThat(afterImport?.streams).isEqualTo(3)
        assertThat(afterImport?.rawEntries).isEqualTo(3)
        // The report describes the store, so the store has to agree with it.
        assertThat(store.channels.value.map { it.name }).containsExactly("One", "Two", "Three").inOrder()
    }

    @Test
    fun `ensureLoaded stays idempotent — it still answers with the load it performed`(): Unit = runBlocking {
        loader.ensureLoaded()
        files.put("${folders.dropFolder}/three.m3u", IMPORTED)
        importer.import(ImportCandidate("${folders.dropFolder}/three.m3u", "three.m3u", IMPORTSIZE, 0L))

        // A second call is a no-op (its contract), so it returns the *boot* report; `report()` is the
        // accessor that tracks the store. Pinned here so nobody "fixes" the wrong one later.
        assertThat(loader.ensureLoaded()?.sourceId).isEqualTo("bundled-fixture")
        assertThat(loader.report()?.channels).isEqualTo(3)
    }

    private fun fixture(): BundledPlaylist = object : BundledPlaylist {
        override val sourceId: String = "bundled-fixture"

        override fun read(): ByteArray = BUNDLED.toByteArray(Charsets.UTF_8)
    }

    private companion object {
        val BUNDLED = """
            #EXTM3U
            #EXTINF:-1 group-title="本地",Bundled-1
            http://bundled.invalid/1.m3u8
        """.trimIndent()

        val IMPORTED = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",One
            http://a.example/1.m3u8
            #EXTINF:-1 group-title="央视",Two
            http://a.example/2.m3u8
            #EXTINF:-1 group-title="本地",Three
            http://a.example/3.m3u8
        """.trimIndent()

        val IMPORTSIZE = IMPORTED.toByteArray(Charsets.UTF_8).size.toLong()
    }
}
