package ilab.iptv.player.core.data.playlist

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
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
 * P2-6 item 3: the SAF half of the local import, off-device.
 *
 * The two things worth pinning here are the ones a device test cannot do on demand: that the
 * persisted read grant is taken (review R-27 — without it the row no longer points at the document it
 * came from) and that both failure modes — the framework refuses the grant, the document is gone —
 * leave the channel list the user is watching untouched.
 *
 * The drop-folder path is unchanged and keeps its own coverage in [LocalPlaylistImportTest]; this file
 * only asserts the two agree on the pipeline and differ in where the bytes came from.
 */
class SafPlaylistImportTest {

    private val folders = ImportFolders(dropFolder = "/app/files/playlists", storeFolder = "/app/files/imports")
    private val files = FakePlaylistFileSystem()
    private val documents = FakeDocumentReader()
    private val permissions = FakeUriPermissionStore()
    private val store = ChannelStore()
    private val catalog = ChannelCatalog(store, FakeClock())
    private val lastImport = LastImportStore(files, folders)
    private val logger = RecordingLogger()
    private val clock = FakeClock()

    private val importer = LocalPlaylistImportRepository(
        files = files,
        folders = folders,
        lastImport = lastImport,
        catalog = catalog,
        logger = logger,
        clock = clock,
        sessionIds = FakeSessionIds(),
        limits = PipelineLimits(),
        dispatchers = TestDispatcherProvider(),
        documents = documents,
        permissions = permissions,
    )

    @Test
    fun `a picked document is imported, granted and remembered with its uri`() = test {
        documents.put(URI, M3U_TWO_CHANNELS, displayName = "picked.m3u")

        val result = importer.importUri(URI)

        val report = (result as ImportResult.Done).report
        assertThat(report.name).isEqualTo("picked.m3u")
        assertThat(report.sourceId).isEqualTo("local:picked.m3u")
        assertThat(report.channels).isEqualTo(2)
        assertThat(store.channels.value.map { it.name }).containsExactly("CCTV-1", "CCTV-2").inOrder()
        // The grant is what makes the row survive a restart with its document still attached.
        assertThat(permissions.took).containsExactly(URI)
        assertThat(permissions.persisted()).containsExactly(URI)
        assertThat(importer.lastImported()?.sourceUri).isEqualTo(URI)
        // The copy is on disk, so the app does not depend on the picker's document staying reachable.
        assertThat(files.text("${folders.storeFolder}/picked.m3u")).isEqualTo(M3U_TWO_CHANNELS)
    }

    @Test
    fun `the remembered record round-trips the picked uri`() = test {
        documents.put(URI, M3U_TWO_CHANNELS, displayName = "picked.m3u")
        importer.importUri(URI)

        // Decoded from the file the app writes, not from the object the importer still holds.
        val decoded = ImportRecord.decode(files.text(lastImport.recordPath()).orEmpty())

        assertThat(decoded?.sourceUri).isEqualTo(URI)
        assertThat(decoded?.name).isEqualTo("picked.m3u")
    }

    @Test
    fun `a record written before SAF has no uri and still decodes`() = test {
        val legacy = """
            {"version":"1","name":"old.m3u","sourceId":"local:old.m3u","copiedPath":"/app/files/imports/old.m3u",
            "sizeBytes":"10","importedAtMs":"1","formatLabel":"m3u","channels":"1","streams":"1"}
        """.trimIndent()

        val decoded = ImportRecord.decode(legacy)

        assertThat(decoded).isNotNull()
        assertThat(decoded?.sourceUri).isNull()
        // Re-encoding one without a uri does not invent a field.
        assertThat(decoded?.encode()).doesNotContain("sourceUri")
    }

    @Test
    fun `a refused grant fails with a readable message and the list survives`() = test {
        catalog.load(M3U_TWO_CHANNELS, "previous")
        documents.put(URI, TXT_TWO_CHANNELS, displayName = "picked.txt")
        permissions.refused += URI

        val result = importer.importUri(URI)

        val failed = result as ImportResult.Failed
        assertThat(failed.code).isEqualTo(EventCodes.DB_FAIL)
        assertThat(failed.message).contains("长期读取权限")
        assertThat(store.channels.value.map { it.name }).containsExactly("CCTV-1", "CCTV-2").inOrder()
        assertThat(lastImport.record()).isNull()
    }

    @Test
    fun `an unreadable document releases the grant and leaves the list alone`() = test {
        catalog.load(M3U_TWO_CHANNELS, "previous")
        documents.put(URI, TXT_TWO_CHANNELS, displayName = "gone.txt")
        documents.unreadable += URI

        val result = importer.importUri(URI)

        val failed = result as ImportResult.Failed
        assertThat(failed.code).isEqualTo(EventCodes.DB_FAIL)
        assertThat(failed.message).contains("读不到这个文件")
        // No point holding a grant for a document that could not be read.
        assertThat(permissions.released).containsExactly(URI)
        assertThat(store.channels.value.map { it.name }).containsExactly("CCTV-1", "CCTV-2").inOrder()
    }

    @Test
    fun `an empty picked document is rejected like an empty dropped file`() = test {
        documents.put(URI, "", displayName = "empty.m3u")

        val result = importer.importUri(URI)

        val failed = result as ImportResult.Failed
        assertThat(failed.code).isEqualTo(EventCodes.SRC_PARSE_FAIL)
        assertThat(failed.message).contains("文件是空的")
        assertThat(store.channels.value).isEmpty()
    }

    @Test
    fun `the drop folder path records no uri`() = test {
        files.put("${folders.dropFolder}/list.m3u", M3U_TWO_CHANNELS)

        importer.import(ImportCandidate("${folders.dropFolder}/list.m3u", "list.m3u", 1L, 0L))

        assertThat(importer.lastImported()?.sourceUri).isNull()
        assertThat(permissions.took).isEmpty()
    }

    @Test
    fun `a document with no display name falls back to the uri's file name`() = test {
        documents.put(RENAMELESS_URI, M3U_TWO_CHANNELS, displayName = null)

        val result = importer.importUri(RENAMELESS_URI)

        val report = (result as ImportResult.Done).report
        assertThat(report.name).isEqualTo("picked.m3u")
        assertThat(report.sourceId).isEqualTo("local:picked.m3u")
    }

    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }

    private companion object {
        const val URI = "content://com.android.providers.downloads.documents/document/7"
        /** A picker that exposes no display name: the importer falls back to the uri's last segment. */
        const val RENAMELESS_URI = "content://com.android.providers.media.documents/document/picked.m3u"

        val M3U_TWO_CHANNELS = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1
            http://a.example/cctv1/index.m3u8
            #EXTINF:-1 tvg-id="cctv2" group-title="央视",CCTV-2
            http://a.example/cctv2/index.m3u8
        """.trimIndent()

        val TXT_TWO_CHANNELS = """
            央视,#genre#
            CCTV-1,http://a.example/txt1/index.m3u8
            CCTV-2,http://a.example/txt2/index.m3u8
        """.trimIndent()
    }
}
