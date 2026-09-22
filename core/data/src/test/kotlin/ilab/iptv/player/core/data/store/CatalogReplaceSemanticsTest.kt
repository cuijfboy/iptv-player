package ilab.iptv.player.core.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.playlist.FakeDocumentReader
import ilab.iptv.player.core.data.playlist.FakePlaylistFileSystem
import ilab.iptv.player.core.data.playlist.FakeUriPermissionStore
import ilab.iptv.player.core.data.playlist.LastImportStore
import ilab.iptv.player.core.data.playlist.LocalPlaylistImportRepository
import ilab.iptv.player.core.data.refresh.FakeSessionIds
import ilab.iptv.player.core.data.test
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 卡 **BUG-STALE-STREAM** — 导入的语义是**整体替换**，同名频道下的旧流也必须消失。
 *
 * The defect (dev-a-21, `docs/05-过程记录/30-BUG013导入弹窗修复.md` §6.1): `RoomCatalogWriter`
 * upserts channels on `(name_key, group_key)` and streams on `(channel_id, url_hash)`, and only
 * deleted the channels that were *not* in the incoming catalog. A channel that survives by name kept
 * every stream the previous playlist had hung on it, so importing an 8-channel / 10-stream playlist
 * on top of the 658-channel fixture showed **「流 22」** with 3–4 streams on one row — the fixture's
 * dead `p12.demo.invalid` URLs sitting there as fail-over candidates.
 *
 * god's ruling (2026-09-22): **import = whole replace.** After it, every stream that is not in the new
 * catalog is gone — including the old streams of a channel that still exists by name. Channel-level
 * *user* state (favourite / hidden / sort order / channel number / EPG binding) is not source data and
 * must survive (`docs/02` §5.1 + P2-2's column split in `ChannelDao.upsertAll`).
 *
 * These tests drive the **real importer** (`LocalPlaylistImportRepository` → `ChannelCatalog` →
 * `CatalogSink`), not the sink alone: the promise is about the flow the user runs. The Room rig is
 * [RoomFixtures.Rig], the same production wiring `PersistenceModule` binds.
 */
@RunWith(AndroidJUnit4::class)
class CatalogReplaceSemanticsTest {

    private val files = FakePlaylistFileSystem()
    private var database: ilab.iptv.player.core.database.IptvDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun `a same-name channel loses the previous playlist's streams`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        assertThat(rig.database.channelDao().count()).isEqualTo(658)

        // The fixture's CCTV1 is the reported case: same name and group, dead demo URLs (two of them:
        // the fixture carries a second CCTV1 entry, which is how 670 streams fit 658 channels).
        assertThat(streamUrls(rig, "CCTV1")).hasSize(2)
        assertThat(streamUrls(rig, "CCTV1").all { it.startsWith("http://p12.demo.invalid/") }).isTrue()

        import(rig, "first.m3u", FIRST)
        // Import is a replace: the 658 fixture channels (and their 670 streams) are gone.
        assertThat(rig.database.channelDao().count()).isEqualTo(1)
        assertThat(streamCount(rig)).isEqualTo(1)
        assertThat(streamUrls(rig, "CCTV1")).containsExactly("http://a.example/cctv1.m3u8")

        import(rig, "second.m3u", SECOND)
        // CCTV1 survives by `(name_key, group_key)` — and its stream set is the new one, not a merge.
        assertThat(rig.database.channelDao().count()).isEqualTo(2)
        assertThat(streamUrls(rig, "CCTV1")).containsExactly("http://b.example/cctv1.m3u8")
        assertThat(streamUrls(rig, "CCTV2"))
            .containsExactly("http://b.example/cctv2-a.m3u8", "http://b.example/cctv2-b.m3u8")
        // The counts the header reads ("频道 2 / 流 3") equal the playlist, and no stale URL is left.
        assertThat(streamCount(rig)).isEqualTo(3)
        assertThat(allStreamUrls(rig).none { it.startsWith("http://p12.demo.invalid/") }).isTrue()
        assertThat(allStreamUrls(rig)).doesNotContain("http://a.example/cctv1.m3u8")
    }

    @Test
    fun `the reported case — an 8 channel 10 stream list on top of the fixture stores 10 streams`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        // What the fixture alone holds for the eight names this playlist uses: those are the rows the
        // old writer left behind (the device showed 「流 22」 = 10 incoming + the fixture's leftovers).
        val fixtureStreamsForTheSameNames = listOf(
            "CCTV1", "CCTV2", "CCTV3", "CCTV4", "CCTV5", "CCTV6", "CCTV7", "CCTV8",
        ).sumOf { streamUrls(rig, it).size }
        assertThat(fixtureStreamsForTheSameNames).isGreaterThan(10)

        import(rig, "eight.m3u", EIGHT_CHANNELS_TEN_STREAMS)

        assertThat(rig.database.channelDao().count()).isEqualTo(8)
        // The number the header reads: exactly this playlist, no fixture leftovers.
        assertThat(streamCount(rig)).isEqualTo(10)
        assertThat(allStreamUrls(rig).all { it.startsWith("http://list.example/") }).isTrue()
        val persisted = rig.logger.events.last { it.code == EventCodes.DB_UPSERT }
        assertThat(persisted.fields["streamsRemoved"]).isEqualTo(fixtureStreamsForTheSameNames)
    }

    @Test
    fun `a user's favourite, hidden flag, sort order and channel number survive a replace`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        import(rig, "first.m3u", FIRST)

        val id = channelId(rig, "CCTV1")
        val at = RoomFixtures.clock().nowMs()
        rig.database.channelDao().setFavorite(id, true, at)
        rig.database.channelDao().setHidden(id, true, at)
        rig.database.channelDao().setSortOrder(id, 7, at)
        rig.database.channelDao().setChannelNo(id, 101, at)

        import(rig, "second.m3u", SECOND)

        val row = rig.database.channelDao().all().single { it.name == "CCTV1" }
        // Same row (the upsert keys on identity, not on the id), so nothing the user owns was reset…
        assertThat(row.id).isEqualTo(id)
        assertThat(row.favorite).isTrue()
        assertThat(row.hidden).isTrue()
        assertThat(row.sortOrder).isEqualTo(7)
        assertThat(row.channelNo).isEqualTo(101)
        // …while the source-owned part of the row is the new playlist's.
        assertThat(streamUrls(rig, "CCTV1")).containsExactly("http://b.example/cctv1.m3u8")
    }

    @Test
    fun `a channel the new playlist dropped is removed with its streams`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        import(rig, "two.m3u", TWO_CHANNELS)
        assertThat(rig.database.channelDao().count()).isEqualTo(2)
        assertThat(streamCount(rig)).isEqualTo(3)

        import(rig, "one.m3u", ONE_CHANNEL)

        assertThat(rig.database.channelDao().count()).isEqualTo(1)
        assertThat(rig.database.channelDao().all().map { it.name }).containsExactly("Keeper")
        // The dropped channel took its streams with it (foreign key cascade), and the new channel's
        // stream is in place.
        assertThat(streamCount(rig)).isEqualTo(1)
        assertThat(allStreamUrls(rig)).containsExactly("http://new.example/keeper.m3u8")
    }

    @Test
    fun `a brand-new channel is added without disturbing the one that stays`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        import(rig, "one.m3u", ONE_CHANNEL)
        import(rig, "two.m3u", TWO_CHANNELS)

        assertThat(rig.database.channelDao().all().map { it.name })
            .containsExactly("Keeper", "Added")
        assertThat(streamUrls(rig, "Keeper")).containsExactly("http://new.example/keeper.m3u8")
    }

    @Test
    fun `the stored counts match the numbers the import reported`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        import(rig, "first.m3u", FIRST)

        files.put("$DROP/second.m3u", SECOND)
        val result = importerOver(rig).import(candidate("second.m3u", SECOND))
        assertThat(result).isInstanceOf(ImportResult.Done::class.java)

        // The report is what the UI/诊断 shows; after a same-name replace it must still describe the
        // stored table exactly — that is the bug (report said 10, the list showed 22).
        val report = requireNotNull(rig.catalog.lastReport())
        assertThat(report.channels).isEqualTo(2)
        assertThat(report.streams).isEqualTo(3)
        assertThat(rig.database.channelDao().count()).isEqualTo(report.channels)
        assertThat(streamCount(rig)).isEqualTo(report.streams)
    }

    @Test
    fun `importing the same playlist twice changes nothing`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()
        import(rig, "second.m3u", SECOND)
        val idsAfterFirst = channelIdsByName(rig)
        val streamIdsAfterFirst = streamIds(rig)

        import(rig, "second.m3u", SECOND)

        assertThat(channelIdsByName(rig)).isEqualTo(idsAfterFirst)
        assertThat(streamIds(rig)).isEqualTo(streamIdsAfterFirst)
        assertThat(streamCount(rig)).isEqualTo(3)
        // Nothing was stale on the second pass, so the stream trim deleted nothing.
        val persisted = rig.logger.events.last { it.code == EventCodes.DB_UPSERT }
        assertThat(persisted.fields["streamsRemoved"]).isEqualTo(0)
    }

    @Test
    fun `both sinks replace the stream set the same way`() = test {
        val rig = rig()
        assertThat(rig.seeder.ensureSeeded()).isTrue()

        // The in-memory sink is the test double for the same contract; run the identical catalog
        // through it and compare what a reader would see.
        val store = ChannelStore()
        val memoryCatalog = ChannelCatalog(store, RoomFixtures.clock())

        import(rig, "first.m3u", FIRST)
        memoryCatalog.commit(memoryCatalog.prepare(FIRST, "local:first.m3u"))

        import(rig, "second.m3u", SECOND)
        memoryCatalog.commit(memoryCatalog.prepare(SECOND, "local:second.m3u"))

        assertThat(observable(store.channels.value, store.streams.value))
            .isEqualTo(observable(rig))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun rig(): RoomFixtures.Rig {
        val db = RoomFixtures.inMemoryDatabase()
        database = db
        return RoomFixtures.Rig(db)
    }

    private fun importerOver(rig: RoomFixtures.Rig): PlaylistImportPort = LocalPlaylistImportRepository(
        dispatchers = TestDispatcherProvider(),
        files = files,
        folders = FOLDERS,
        lastImport = LastImportStore(files, FOLDERS),
        catalog = rig.catalog,
        logger = rig.logger,
        clock = RoomFixtures.clock(),
        sessionIds = FakeSessionIds(),
        limits = PipelineLimits(),
        documents = FakeDocumentReader(),
        permissions = FakeUriPermissionStore(),
    )

    private suspend fun import(rig: RoomFixtures.Rig, name: String, text: String) {
        files.put("$DROP/$name", text)
        val result = importerOver(rig).import(candidate(name, text))
        assertThat(result).isInstanceOf(ImportResult.Done::class.java)
    }

    private fun candidate(name: String, text: String) = ImportCandidate(
        path = "$DROP/$name",
        name = name,
        sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        modifiedAtMs = files.clockMs,
    )

    private suspend fun channelId(rig: RoomFixtures.Rig, name: String): Long =
        rig.database.channelDao().all().single { it.name == name }.id

    private suspend fun channelIdsByName(rig: RoomFixtures.Rig): Map<String, Long> =
        rig.database.channelDao().all().associate { it.name to it.id }

    private suspend fun streamUrls(rig: RoomFixtures.Rig, channel: String): List<String> =
        rig.database.streamDao().allForChannel(channelId(rig, channel)).map { it.url }.sorted()

    private suspend fun allStreamUrls(rig: RoomFixtures.Rig): List<String> =
        rig.database.channelDao().all().flatMap { streamUrls(rig, it.name) }

    private suspend fun streamIds(rig: RoomFixtures.Rig): List<Long> =
        rig.database.channelDao().all().flatMap { channel ->
            rig.database.streamDao().allForChannel(channel.id).map { it.id }
        }.sorted()

    private suspend fun streamCount(rig: RoomFixtures.Rig): Int =
        rig.database.streamDao().countByChannel().sumOf { it.count }

    /** What a reader sees in Room, keyed by channel identity rather than by the reusable row id. */
    private suspend fun observable(rig: RoomFixtures.Rig): Map<String, List<String>> =
        rig.database.channelDao().all().associate { channel ->
            "${channel.name}/${channel.groupKey}" to
                rig.database.streamDao().allForChannel(channel.id).map { it.url }.sorted()
        }

    /** The same view over the in-memory sink's snapshot. */
    private fun observable(channels: List<Channel>, streams: List<Stream>): Map<String, List<String>> {
        val urlsByChannel = streams.groupBy({ it.channelId }, { it.url })
        return channels.associate { channel ->
            "${channel.name}/${channel.groupKey}" to (urlsByChannel[channel.id] ?: emptyList()).sorted()
        }
    }

    private companion object {
        const val DROP = "/app/files/playlists"
        const val STORE = "/app/files/imports"
        val FOLDERS = ImportFolders(dropFolder = DROP, storeFolder = STORE)

        /** Same name and group as the fixture's CCTV1 — the reported 「流 22」 case. */
        val FIRST = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV1
            http://a.example/cctv1.m3u8
        """.trimIndent()

        /** CCTV1 again with a *different* URL, plus a brand-new channel. */
        val SECOND = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV1
            http://b.example/cctv1.m3u8
            #EXTINF:-1 group-title="央视",CCTV2
            http://b.example/cctv2-a.m3u8
            #EXTINF:-1 group-title="央视",CCTV2
            http://b.example/cctv2-b.m3u8
        """.trimIndent()

        val ONE_CHANNEL = """
            #EXTM3U
            #EXTINF:-1 group-title="本地",Keeper
            http://new.example/keeper.m3u8
        """.trimIndent()

        val TWO_CHANNELS = """
            #EXTM3U
            #EXTINF:-1 group-title="本地",Keeper
            http://new.example/keeper.m3u8
            #EXTINF:-1 group-title="本地",Added
            http://new.example/added-a.m3u8
            #EXTINF:-1 group-title="本地",Added
            http://new.example/added-b.m3u8
        """.trimIndent()

        /**
         * The shape dev-a-21 imported on the device: 8 channels, 10 streams (CCTV2 and CCTV3 carry two
         * each), all eight names and the group already present in the bundled fixture.
         */
        val EIGHT_CHANNELS_TEN_STREAMS = buildString {
            appendLine("#EXTM3U")
            listOf(1, 2, 2, 3, 3, 4, 5, 6, 7, 8).forEachIndexed { index, number ->
                appendLine("#EXTINF:-1 group-title=\"央视\",CCTV$number")
                appendLine("http://list.example/cctv$number-$index.m3u8")
            }
        }.trim()
    }
}
