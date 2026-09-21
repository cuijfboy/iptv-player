package ilab.iptv.player.core.data.catalog

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.playlist.FakePlaylistFileSystem
import ilab.iptv.player.core.data.playlist.ImportRecord
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.refresh.RecordingLogger
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.playlist.ImportFolders
import org.junit.Test

/**
 * What the app opens with after a restart: the remembered import if it is usable, the bundled
 * fixture otherwise, and never an empty list.
 *
 * This is the "重启后仍可用" acceptance item, tested off-device through the filesystem seam — the
 * alternative would be to install a release APK, import a playlist, force-stop the app and read the
 * channel count off a TV screenshot for each of the four branches below.
 */
class ChannelCatalogLoaderTest {

    private val folders = ImportFolders(dropFolder = "/app/files/playlists", storeFolder = "/app/files/imports")
    private val files = FakePlaylistFileSystem()
    private val store = ChannelStore()
    private val catalog = ChannelCatalog(store, FakeClock())
    private val logger = RecordingLogger()
    private val lastImport = LastImportStore(files, folders)
    private val bundled = FakeBundledPlaylist(BUNDLED_LIST)

    @Test
    fun `with nothing imported it loads the bundled fixture`() = test {
        val report = ChannelCatalogLoader(TestDispatcherProvider(), bundled, lastImport, catalog, logger).ensureLoaded()

        assertThat(report?.sourceId).isEqualTo("bundled-fixture")
        assertThat(store.channels.value.map { it.name }).containsExactly("Bundled-1")
    }

    @Test
    fun `a remembered import wins over the bundled fixture`() = test {
        remember("imported.m3u", IMPORTED_LIST)

        val report = ChannelCatalogLoader(TestDispatcherProvider(), bundled, lastImport, catalog, logger).ensureLoaded()

        assertThat(report?.sourceId).isEqualTo("local:imported.m3u")
        assertThat(store.channels.value.map { it.name }).containsExactly("Imported-1", "Imported-2").inOrder()
        // The fixture is not even read when there is an import to restore.
        assertThat(bundled.reads).isEqualTo(0)
    }

    @Test
    fun `a remembered file with no channels falls back to the bundled fixture`() = test {
        remember("junk.m3u", "#EXTM3U\n#EXTINF:-1,no url here\n")

        val report = ChannelCatalogLoader(TestDispatcherProvider(), bundled, lastImport, catalog, logger).ensureLoaded()

        assertThat(report?.sourceId).isEqualTo("bundled-fixture")
        assertThat(store.channels.value.map { it.name }).containsExactly("Bundled-1")
    }

    @Test
    fun `a record whose copy is gone falls back to the bundled fixture`() = test {
        remember("imported.m3u", IMPORTED_LIST)
        files.delete("$STORE/imported.m3u")

        val report = ChannelCatalogLoader(TestDispatcherProvider(), bundled, lastImport, catalog, logger).ensureLoaded()

        assertThat(report?.sourceId).isEqualTo("bundled-fixture")
        assertThat(store.channels.value.map { it.name }).containsExactly("Bundled-1")
    }

    @Test
    fun `ensureLoaded loads once and then reuses the report`() = test {
        val loader = ChannelCatalogLoader(TestDispatcherProvider(), bundled, lastImport, catalog, logger)

        val first = loader.ensureLoaded()
        val second = loader.ensureLoaded()

        assertThat(second).isSameInstanceAs(first)
        assertThat(bundled.reads).isEqualTo(1)
        assertThat(store.channels.value).hasSize(1)
    }

    @Test
    fun `an unreadable record still starts the app on the fixture`() = test {
        files.put(lastImport.recordPath(), "{ not json")

        val report = ChannelCatalogLoader(TestDispatcherProvider(), bundled, lastImport, catalog, logger).ensureLoaded()

        assertThat(report?.sourceId).isEqualTo("bundled-fixture")
    }

    /** Writes exactly what a successful import leaves behind: the copy plus its record. */
    private fun remember(name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val copiedPath = "$STORE/$name"
        files.putBytes(copiedPath, bytes)
        lastImport.write(
            ImportRecord(
                name = name,
                sourceId = "local:$name",
                copiedPath = copiedPath,
                sizeBytes = bytes.size.toLong(),
                importedAtMs = 1_700_000_000_000L,
                formatLabel = "m3u",
                channels = 2,
                streams = 2,
            ),
            bytes,
        )
    }

    private fun test(block: suspend () -> Unit): Unit = kotlinx.coroutines.runBlocking { block() }

    private class FakeBundledPlaylist(private val text: String) : BundledPlaylist {
        override val sourceId = "bundled-fixture"
        var reads = 0
            private set

        override fun read(): ByteArray {
            reads++
            return text.toByteArray(Charsets.UTF_8)
        }
    }

    private companion object {
        const val STORE = "/app/files/imports"

        val BUNDLED_LIST = """
            #EXTM3U
            #EXTINF:-1 group-title="本地",Bundled-1
            http://bundled.invalid/1.m3u8
        """.trimIndent()

        val IMPORTED_LIST = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",Imported-1
            http://a.example/1.m3u8
            #EXTINF:-1 group-title="央视",Imported-2
            http://a.example/2.m3u8
        """.trimIndent()
    }
}
