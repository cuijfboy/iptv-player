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
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import org.junit.Test

/**
 * The P2-6 slice of local import, end to end but off-device: candidates → parse → store → remember.
 *
 * Every branch here is one a QA run would otherwise have to reproduce by hand on the TV (drop a file
 * with `adb push`, walk the picker, read a toast), and several of them — an unreadable file, a full
 * store folder — are not reproducible on demand at all.
 */
class LocalPlaylistImportTest {

    private val folders = ImportFolders(dropFolder = "/app/files/playlists", storeFolder = "/app/files/imports")
    private val files = FakePlaylistFileSystem()
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
    )

    @Test
    fun `candidates list only non-empty playlist files, by name`() = test {
        files.put("${folders.dropFolder}/b-list.m3u", M3U_TWO_CHANNELS)
        files.put("${folders.dropFolder}/a-list.txt", TXT_TWO_CHANNELS)
        files.put("${folders.dropFolder}/notes.md", "not a playlist")
        files.put("${folders.dropFolder}/empty.m3u", "")
        files.put("${folders.dropFolder}/sub/nested.m3u", M3U_TWO_CHANNELS)

        val candidates = importer.candidates()

        assertThat(candidates.map { it.name }).containsExactly("a-list.txt", "b-list.m3u").inOrder()
        assertThat(candidates.first().sizeBytes).isEqualTo(sizeOf(TXT_TWO_CHANNELS))
        assertThat(candidates.map { it.path }).containsExactly(
            "${folders.dropFolder}/a-list.txt",
            "${folders.dropFolder}/b-list.m3u",
        )
    }

    @Test
    fun `an M3U import replaces the catalog and reports what it became`() = test {
        files.put("${folders.dropFolder}/list.m3u", M3U_TWO_CHANNELS)

        val result = importer.import(candidate("list.m3u", M3U_TWO_CHANNELS))

        val report = (result as ImportResult.Done).report
        assertThat(report.name).isEqualTo("list.m3u")
        assertThat(report.sourceId).isEqualTo("local:list.m3u")
        assertThat(report.formatLabel).isEqualTo("m3u")
        assertThat(report.rawEntries).isEqualTo(2)
        assertThat(report.skipped).isEqualTo(0)
        assertThat(report.channels).isEqualTo(2)
        assertThat(report.streams).isEqualTo(2)
        assertThat(report.copiedPath).isEqualTo("${folders.storeFolder}/list.m3u")

        // The list the screen renders comes from the store, so this is the "immediately refreshed" part.
        assertThat(store.channels.value.map { it.name }).containsExactly("CCTV-1", "CCTV-2").inOrder()
        assertThat(store.channels.value.map { it.group }).containsExactly(
            ChannelGroup.CCTV,
            ChannelGroup.CCTV,
        ).inOrder()
        // Every stream carries the import's source id, so a later refresh can tell where it came from.
        assertThat(store.streams.value.map { it.sourceId }.toSet()).containsExactly("local:list.m3u")
        assertThat(logger.count(EventCodes.SRC_PARSE_OK)).isEqualTo(1)
    }

    @Test
    fun `a TXT list goes through the same pipeline`() = test {
        files.put("${folders.dropFolder}/live.txt", TXT_TWO_CHANNELS)

        val result = importer.import(candidate("live.txt", TXT_TWO_CHANNELS))

        val report = (result as ImportResult.Done).report
        assertThat(report.formatLabel).isEqualTo("txt")
        assertThat(report.channels).isEqualTo(2)
        assertThat(store.channels.value.map { it.groupTitle }).containsExactly("央视", "央视").inOrder()
    }

    @Test
    fun `GB18030 bytes are decoded instead of being dropped`() = test {
        val bytes = GB18030_M3U.toByteArray(charset("GB18030"))
        files.putBytes("${folders.dropFolder}/gb.m3u", bytes)

        val result = importer.import(
            ImportCandidate(
                path = "${folders.dropFolder}/gb.m3u",
                name = "gb.m3u",
                sizeBytes = bytes.size.toLong(),
                modifiedAtMs = files.clockMs,
            ),
        )

        assertThat((result as ImportResult.Done).report.channels).isEqualTo(1)
        assertThat(store.channels.value.single().name).isEqualTo("中央一台")
    }

    @Test
    fun `a file with no usable channels is rejected and the current list survives`() = test {
        catalog.load(M3U_TWO_CHANNELS, "previous")
        val broken = "#EXTM3U\n#EXTINF:-1,Broken row with no URL\n"
        files.put("${folders.dropFolder}/broken.m3u", broken)

        val result = importer.import(candidate("broken.m3u", broken))

        val failed = result as ImportResult.Failed
        assertThat(failed.code).isEqualTo(EventCodes.SRC_PARSE_FAIL)
        assertThat(failed.message).contains("没有可用频道")
        // The important half: the user keeps looking at what they were looking at.
        assertThat(store.channels.value.map { it.name }).containsExactly("CCTV-1", "CCTV-2").inOrder()
        assertThat(lastImport.record()).isNull()
    }

    @Test
    fun `an empty file is rejected`() = test {
        val path = "${folders.dropFolder}/empty.m3u"
        files.put(path, "")

        val result = importer.import(ImportCandidate(path, "empty.m3u", 0, files.clockMs))

        assertThat((result as ImportResult.Failed).message).contains("文件是空的")
        assertThat(store.channels.value).isEmpty()
    }

    @Test
    fun `a file that cannot be read is reported as such`() = test {
        val path = "${folders.dropFolder}/gone.m3u"
        files.put(path, M3U_TWO_CHANNELS)
        files.unreadable += path

        val result = importer.import(candidate("gone.m3u", M3U_TWO_CHANNELS))

        val failed = result as ImportResult.Failed
        assertThat(failed.code).isEqualTo(EventCodes.DB_FAIL)
        assertThat(failed.message).contains("读不到这个文件")
        assertThat(store.channels.value).isEmpty()
    }

    @Test
    fun `a file over the fetch byte ceiling is rejected instead of being read whole`() = test {
        val big = "#EXTM3U\n" + "#EXTINF:-1,Big\nhttp://a.example/big.m3u8\n".repeat(200)
        files.put("${folders.dropFolder}/big.m3u", big)
        val capped = LocalPlaylistImportRepository(
            files, folders, lastImport, catalog, logger, clock, FakeSessionIds(), PipelineLimits(maxBytes = 512),
            TestDispatcherProvider(),
        )

        val result = capped.import(candidate("big.m3u", big))

        val failed = result as ImportResult.Failed
        assertThat(failed.message).contains("文件过大")
        assertThat(store.channels.value).isEmpty()
    }

    @Test
    fun `a failed copy is reported and the list is not replaced`() = test {
        catalog.load(M3U_TWO_CHANNELS, "previous")
        files.put("${folders.dropFolder}/new.m3u", TXT_TWO_CHANNELS)
        files.unwritable += "${folders.storeFolder}/new.m3u"

        val result = importer.import(candidate("new.m3u", TXT_TWO_CHANNELS))

        val failed = result as ImportResult.Failed
        assertThat(failed.code).isEqualTo(EventCodes.DB_FAIL)
        assertThat(failed.message).contains("无法保存")
        assertThat(store.channels.value.map { it.name }).containsExactly("CCTV-1", "CCTV-2").inOrder()
        assertThat(lastImport.record()).isNull()
    }

    @Test
    fun `an import is remembered with its copy`() = test {
        files.put("${folders.dropFolder}/list.m3u", M3U_TWO_CHANNELS)

        importer.import(candidate("list.m3u", M3U_TWO_CHANNELS))

        val remembered = importer.lastImported()
        assertThat(remembered?.name).isEqualTo("list.m3u")
        assertThat(remembered?.sourceId).isEqualTo("local:list.m3u")
        assertThat(remembered?.sizeBytes).isEqualTo(sizeOf(M3U_TWO_CHANNELS))
        // The copy is byte-for-byte what came in, so a restart parses the same list.
        assertThat(files.text("${folders.storeFolder}/list.m3u")).isEqualTo(M3U_TWO_CHANNELS)
        assertThat(files.contains(lastImport.recordPath())).isTrue()
    }

    @Test
    fun `importing a different file replaces the kept copy instead of piling up`() = test {
        files.put("${folders.dropFolder}/first.m3u", M3U_TWO_CHANNELS)
        files.put("${folders.dropFolder}/second.txt", TXT_TWO_CHANNELS)

        importer.import(candidate("first.m3u", M3U_TWO_CHANNELS))
        importer.import(candidate("second.txt", TXT_TWO_CHANNELS))

        assertThat(files.contains("${folders.storeFolder}/first.m3u")).isFalse()
        assertThat(files.contains("${folders.storeFolder}/second.txt")).isTrue()
        assertThat(importer.lastImported()?.name).isEqualTo("second.txt")
        assertThat(store.channels.value).hasSize(2)
    }

    /** JUnit4 requires a `void` test method, so suspend bodies are wrapped, not returned. */
    private fun test(block: suspend () -> Unit): Unit = kotlinx.coroutines.runBlocking { block() }

    private fun candidate(name: String, text: String) = ImportCandidate(
        path = "${folders.dropFolder}/$name",
        name = name,
        sizeBytes = sizeOf(text),
        modifiedAtMs = files.clockMs,
    )

    /** Bytes, not characters: the fixtures contain CJK group names, which are 3 bytes each. */
    private fun sizeOf(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    private companion object {
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

        const val GB18030_M3U = "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",中央一台\nhttp://a.example/gb1/index.m3u8\n"
    }
}
