package ilab.iptv.player.core.data.dispatchers

import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.data.catalog.BundledPlaylist
import ilab.iptv.player.core.data.catalog.CatalogLoadReport
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.catalog.ChannelCatalogLoader
import ilab.iptv.player.core.data.playlist.FakeDocumentReader
import ilab.iptv.player.core.data.playlist.FakePlaylistFileSystem
import ilab.iptv.player.core.data.playlist.FakeUriPermissionStore
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.playlist.LocalPlaylistImportRepository
import ilab.iptv.player.core.data.playlist.RememberedPlaylist
import ilab.iptv.player.core.data.playlist.RememberedPlaylistSource
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.refresh.FakeSessionIds
import ilab.iptv.player.core.data.refresh.RecordingLogger
import ilab.iptv.player.core.data.refresh.RefreshSourcesUseCase
import ilab.iptv.player.core.data.repository.InMemoryStreamRepository
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.domain.selection.DefaultStreamSelector
import ilab.iptv.player.core.domain.scoring.DefaultScorer
import ilab.iptv.player.core.data.repository.InMemoryChannelRepository
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.model.ValidationResult
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import ilab.iptv.player.core.source.provider.SourceProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The proof that "inject the dispatchers" is not a comment: each class below must do its work on the
 * `DispatcherProvider` it was handed, not on a hard-coded `Dispatchers.IO` — and the same code must
 * run with a `TestDispatcher` swapped in (docs/02 §4.5 C6, §10).
 *
 * The three assertions are deliberately different kinds:
 * 1. [the catalog loader reads the remembered copy on the injected dispatcher] names a real thread —
 *    if the class still used `Dispatchers.IO`, the recorded name would be an IO worker's, not
 *    `test-io`.
 * 2. [the refresh pipeline fetches on the injected dispatcher] does the same for the pipeline that
 *    `P2-4a` recorded the TODO against; the collector here is the test thread, so "it ran somewhere
 *    else, and that somewhere is the injected dispatcher" is a real observation.
 * 3. [an import driven by a TestDispatcher still completes] is the swap itself: every member is a
 *    `TestDispatcher`, i.e. the code cannot depend on a production dispatcher to finish.
 */
class InjectedDispatchersTest {

    private val folders = ImportFolders(dropFolder = "/app/files/playlists", storeFolder = "/app/files/imports")

    @Test
    fun `the catalog loader reads the remembered copy on the injected dispatcher`(): Unit = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        val remembered = object : RememberedPlaylistSource {
            override fun read(): RememberedPlaylist? {
                threads += Thread.currentThread().name
                return null
            }
        }
        val loader = ChannelCatalogLoader(
            bundled = fixture(),
            remembered = remembered,
            catalog = ChannelCatalog(ChannelStore(), FakeClock()),
            logger = RecordingLogger(),
            dispatchers = TestDispatcherProvider(namedThread("test-io")),
        )

        loader.ensureLoaded()

        // kotlinx appends `@coroutine#N` to the thread name while a coroutine runs, so match the
        // prefix: the point is that an `iptv-*`-less thread we named is the one that did the read.
        assertThat(threads).hasSize(1)
        assertThat(threads.first()).startsWith("test-io")
    }

    @Test
    fun `the refresh pipeline fetches on the injected dispatcher`(): Unit = runBlocking {
        val fetchThreads = CopyOnWriteArrayList<String>()
        val provider = object : SourceProvider {
            override val id: String = "src"
            override val label: String = "src"
            override val kind: SourceKind = SourceKind.M3U

            override suspend fun fetch(clock: Clock): AppResult<List<RawEntry>> {
                fetchThreads += Thread.currentThread().name
                return AppResult.Ok(
                    listOf(
                        RawEntry(
                            name = "C1",
                            url = "http://stream.invalid/1.m3u8",
                            groupTitle = "G",
                            sourceId = id,
                        ),
                    ),
                )
            }
        }
        val store = ChannelStore()
        val useCase = RefreshSourcesUseCase(
            providers = setOf(provider),
            validators = emptySet(),
            streamRepository = InMemoryStreamRepository(store),
            channelRepository = InMemoryChannelRepository(store, NoopBootstrapper),
            catalogSink = store,
            scorer = DefaultScorer(),
            selector = DefaultStreamSelector(),
            device = DeviceProfile(
                abi = "arm64-v8a",
                sdk = 30,
                ramMb = 2048,
                audioPassthrough = emptySet(),
                maxWidth = 1920,
                maxHeight = 1080,
                maxFrameRate = 60f,
            ),
            limits = PipelineLimits(),
            clock = FakeClock(),
            logger = RecordingLogger(),
            sessionIds = FakeSessionIds(),
            playback = PlaybackPrioritySignal { false },
            dispatchers = TestDispatcherProvider(namedThread("test-io")),
        )

        useCase(RefreshOptions(trigger = RefreshTrigger.MANUAL)).toList()

        assertThat(fetchThreads).hasSize(1)
        assertThat(fetchThreads.first()).startsWith("test-io")
        assertThat(Thread.currentThread().name).doesNotContain("test-io")
    }

    @Test
    fun `an import driven by a TestDispatcher still completes`(): Unit = runBlocking {
        val files = FakePlaylistFileSystem()
        val store = ChannelStore()
        val list = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",One
            http://a.example/1.m3u8
            #EXTINF:-1 group-title="央视",Two
            http://a.example/2.m3u8
        """.trimIndent()
        files.put("${folders.dropFolder}/list.m3u", list)
        val importer = LocalPlaylistImportRepository(
            files = files,
            folders = folders,
            lastImport = LastImportStore(files, folders),
            catalog = ChannelCatalog(store, FakeClock()),
            logger = RecordingLogger(),
            clock = FakeClock(),
            sessionIds = FakeSessionIds(),
            limits = PipelineLimits(),
            // Everything a TestDispatcher: no production dispatcher is needed to finish.
            dispatchers = TestDispatcherProvider(),
            documents = FakeDocumentReader(),
            permissions = FakeUriPermissionStore(),
        )

        val result = importer.import(ImportCandidate("${folders.dropFolder}/list.m3u", "list.m3u", list.length.toLong(), 0L))

        assertThat(result).isInstanceOf(ImportResult.Done::class.java)
        assertThat(store.channels.value.map { it.name }).containsExactly("One", "Two").inOrder()
    }

    /** Never read on the paths under test; the loader only needs the seam to exist. */
    private fun fixture(): BundledPlaylist = object : BundledPlaylist {
        override val sourceId: String = "bundled-fixture"

        override fun read(): ByteArray = """
            #EXTM3U
            #EXTINF:-1 group-title="本地",Bundled-1
            http://bundled.invalid/1.m3u8
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}

/** Minimal [CatalogBootstrapper] for wiring tests: nothing to load, nothing to report. */
private object NoopBootstrapper : CatalogBootstrapper {
    override suspend fun ensureLoaded(): CatalogLoadReport? = null
}
