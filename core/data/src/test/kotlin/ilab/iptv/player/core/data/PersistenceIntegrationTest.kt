package ilab.iptv.player.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.playlist.FakePlaylistFileSystem
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.playlist.LocalPlaylistImportRepository
import ilab.iptv.player.core.data.refresh.FakeSessionIds
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 收口 acceptance for the P2-1 × P2-6 integration conflict, off-device: **the import writes
 * Room through the one write seam, and a cold start reads it back**.
 *
 * This is the test the two workstreams were missing. Each one passed on its own — P2-1 drove the Room
 * repositories, P2-6 drove the in-memory store — but nothing checked the joint, so "list reads Room,
 * import writes memory" survived review. There are two claims here and both are load-bearing:
 * 1. an import **replaces** the stored catalog in Room (not merges with the 658-channel fixture), and
 * 2. after closing and reopening the database, the imported channels are still the whole list, with
 *    no fixture re-parse.
 *
 * The production wiring is [RoomFixtures.Rig]: `ChannelCatalog` publishes through a `CatalogSink`
 * that is the `RoomCatalogWriter`, exactly as `PersistenceModule` binds it. That the *Hilt* graph
 * resolves to the same shape (and has no `DuplicateBindings`) is proven separately by
 * `./gradlew :app:assembleDebug`, which is the only check that sees the merged component.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceIntegrationTest {

    private val files = FakePlaylistFileSystem()
    private val all = ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null)

    @After
    fun tearDown() {
        RoomFixtures.deleteDatabase(COLD_START_DB)
    }

    @Test
    fun `a local import reaches Room and survives a cold start`() = test {
        RoomFixtures.deleteDatabase(COLD_START_DB)
        val first = RoomFixtures.Rig(RoomFixtures.fileDatabase(COLD_START_DB))
        // First launch: the fixture is seeded, so any channel we see later is not "Room was empty".
        assertThat(first.seeder.ensureSeeded()).isTrue()
        assertThat(first.database.channelDao().count()).isEqualTo(658)

        files.put("$DROP/mine.m3u", IMPORTED_THREE)
        val result = importerOver(first).import(candidate("mine.m3u", IMPORTED_THREE))
        assertThat(result).isInstanceOf(ImportResult.Done::class.java)

        // The import reached Room, and as a *replace*: the 658 fixture channels are gone.
        assertThat(first.database.channelDao().count()).isEqualTo(3)
        assertThat(first.database.streamDao().countByChannel().sumOf { it.count }).isEqualTo(3)
        // And the list the screen reads (Room-backed) is the import.
        assertThat(first.channels.observe(all).first().map { it.channel.name })
            .containsExactly("Imported-A", "Imported-B", "Imported-C")
        first.database.close()

        // Cold start: a brand-new handle on the same file. Room already has rows, so the seeder is a
        // no-op — no asset read, no parse, no write — and the import is still the whole catalog.
        val second = RoomFixtures.Rig(RoomFixtures.fileDatabase(COLD_START_DB))
        assertThat(second.seeder.ensureSeeded()).isFalse()
        assertThat(second.logger.codes).doesNotContain(EventCodes.DB_UPSERT)
        assertThat(second.database.channelDao().count()).isEqualTo(3)
        assertThat(second.channels.observe(all).first().map { it.channel.name })
            .containsExactly("Imported-A", "Imported-B", "Imported-C")
        second.database.close()
    }

    @Test
    fun `a rejected import leaves the Room catalog untouched`() = test {
        val rig = RoomFixtures.Rig(RoomFixtures.inMemoryDatabase())
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        val before = rig.database.channelDao().count()
        val streamsBefore = rig.database.streamDao().countByChannel().sumOf { it.count }
        val importer = importerOver(rig)

        // Empty file.
        files.put("$DROP/empty.m3u", "")
        assertThat(importer.import(candidate("empty.m3u", "")))
            .isInstanceOf(ImportResult.Failed::class.java)
        assertThat(rig.database.channelDao().count()).isEqualTo(before)

        // Parses to nothing usable.
        val junk = "#EXTM3U\n#EXTINF:-1,no url here\n"
        files.put("$DROP/junk.m3u", junk)
        assertThat(importer.import(candidate("junk.m3u", junk)))
            .isInstanceOf(ImportResult.Failed::class.java)
        assertThat(rig.database.channelDao().count()).isEqualTo(before)

        // Over the Fetch byte ceiling: refused before it is read whole (same limit as the pipeline).
        val big = "#EXTM3U\n" + "#EXTINF:-1,Big\nhttp://a.example/big.m3u8\n".repeat(200)
        files.put("$DROP/big.m3u", big)
        assertThat(importerOver(rig, PipelineLimits(maxBytes = 512)).import(candidate("big.m3u", big)))
            .isInstanceOf(ImportResult.Failed::class.java)

        // Nothing changed: same rows, same streams, and the screen still shows the fixture.
        assertThat(rig.database.channelDao().count()).isEqualTo(before)
        assertThat(rig.database.streamDao().countByChannel().sumOf { it.count }).isEqualTo(streamsBefore)
        assertThat(rig.channels.observe(all).first()).hasSize(before)
        rig.database.close()
    }

    /** The importer wired onto the production stack: same `ChannelCatalog` (→ Room) the app injects. */
    private fun importerOver(rig: RoomFixtures.Rig, limits: PipelineLimits = PipelineLimits()): PlaylistImportPort =
        LocalPlaylistImportRepository(
            files = files,
            folders = FOLDERS,
            lastImport = LastImportStore(files, FOLDERS),
            catalog = rig.catalog,
            logger = rig.logger,
            clock = RoomFixtures.clock(),
            sessionIds = FakeSessionIds(),
            limits = limits,
        )

    private fun candidate(name: String, text: String) = ImportCandidate(
        path = "$DROP/$name",
        name = name,
        sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        modifiedAtMs = files.clockMs,
    )

    private companion object {
        const val COLD_START_DB = "p2-integration.db"
        const val DROP = "/app/files/playlists"
        const val STORE = "/app/files/imports"
        val FOLDERS = ImportFolders(dropFolder = DROP, storeFolder = STORE)

        val IMPORTED_THREE = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ia" group-title="我的源",Imported-A
            http://imported.invalid/a.m3u8
            #EXTINF:-1 tvg-id="ib" group-title="我的源",Imported-B
            http://imported.invalid/b.m3u8
            #EXTINF:-1 tvg-id="ic" group-title="我的源",Imported-C
            http://imported.invalid/c.m3u8
        """.trimIndent()
    }
}
