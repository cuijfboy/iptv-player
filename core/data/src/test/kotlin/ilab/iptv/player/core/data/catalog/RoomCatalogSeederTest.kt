package ilab.iptv.player.core.data.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.store.RoomCatalogWriter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/**
 * SNAPSHOT-1: the three behaviours the seeding rule is made of, pinned off-device.
 *
 * The seeder is the only thing that puts a catalog in front of a user who has done nothing, so each
 * branch has a cost if it is wrong:
 * 1. **empty table → seed**: a fresh install (`pm clear`) must end up with the bundled snapshot;
 * 2. **non-empty table → no-op**: an import or an earlier seed must never be overwritten by the
 *    bundled file (that is what makes a user's own list durable);
 * 3. **missing / unusable asset → degrade**: a build with no snapshot, or a truncated one, must leave
 *    the app usable ("no catalog yet") instead of crashing or publishing an empty list.
 *
 * The rig is the production shape ([RoomCatalogWriter] behind `ChannelCatalog.commit`) over an
 * in-memory Room database; only the [BundledPlaylist] is faked, because the assets under test are
 * the *real* ones and `AssetBundledPlaylist` is covered by `SnapshotPlaylistTest`.
 */
@RunWith(AndroidJUnit4::class)
class RoomCatalogSeederTest {

    private val database = RoomFixtures.inMemoryDatabase()
    private val logger = RoomFixtures.RecordingLogger()
    private val catalog = ChannelCatalog(
        RoomCatalogWriter(database, database.channelDao(), database.streamDao(), logger),
        RoomFixtures.clock(),
    )

    private suspend fun names(): List<String> =
        database.channelDao().observeAll().first().map { it.channel.name }

    private fun seeder(read: () -> ByteArray) = RoomCatalogSeeder(
        dispatchers = TestDispatcherProvider(),
        bundled = object : BundledPlaylist {
            override val sourceId: String = AssetBundledPlaylist.SNAPSHOT_SOURCE_ID
            override fun read(): ByteArray = read()
        },
        catalog = catalog,
        channelDao = database.channelDao(),
        logger = logger,
    )

    @Test
    fun `an empty table is seeded from the bundled snapshot`() = test {
        val seeder = seeder { SNAPSHOT.toByteArray() }

        assertThat(seeder.ensureSeeded()).isTrue()
        assertThat(database.channelDao().count()).isEqualTo(3)
        assertThat(logger.codes).contains(EventCodes.SRC_PARSE_OK)
        assertThat(names())
            .containsExactly("Snapshot-A", "Snapshot-B", "Snapshot-C")
    }

    @Test
    fun `a second call on the same instance does not seed twice`() = test {
        val seeder = seeder { SNAPSHOT.toByteArray() }

        assertThat(seeder.ensureSeeded()).isTrue()
        assertThat(seeder.ensureSeeded()).isFalse()
        assertThat(logger.codes.count { it == EventCodes.SRC_PARSE_OK }).isEqualTo(1)
    }

    @Test
    fun `an existing catalog is never overwritten by the snapshot`() = test {
        // Someone already has a list: the import path writes through the same sink, so this is the
        // same Room state the seeder would meet after a real import.
        catalog.load(EXISTING, "local:mine.m3u")
        assertThat(database.channelDao().count()).isEqualTo(2)

        val seeder = seeder { SNAPSHOT.toByteArray() }
        assertThat(seeder.ensureSeeded()).isFalse()

        // Unchanged: not merged, not replaced, and the snapshot was never even parsed.
        assertThat(database.channelDao().count()).isEqualTo(2)
        assertThat(names())
            .containsExactly("Mine-A", "Mine-B")
        assertThat(logger.codes).doesNotContain(EventCodes.SRC_PARSE_OK)
    }

    @Test
    fun `a missing snapshot degrades to no catalog instead of crashing`() = test {
        val seeder = seeder { throw FileNotFoundException("snapshot/channels.m3u") }

        assertThat(seeder.ensureSeeded()).isFalse()
        assertThat(database.channelDao().count()).isEqualTo(0)
        assertThat(logger.codes).contains(EventCodes.DB_FAIL)
    }

    @Test
    fun `a snapshot that parses into nothing is refused, not published`() = test {
        val seeder = seeder { GARBAGE.toByteArray() }

        assertThat(seeder.ensureSeeded()).isFalse()
        assertThat(database.channelDao().count()).isEqualTo(0)
        assertThat(logger.codes).contains(EventCodes.SRC_PARSE_FAIL)
        assertThat(logger.codes).doesNotContain(EventCodes.SRC_PARSE_OK)
    }

    private companion object {
        const val SNAPSHOT = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-id=\"A\" group-title=\"央视\",Snapshot-A\nhttp://host/a.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"B\" group-title=\"卫视\",Snapshot-B\nhttp://host/b.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"C\" group-title=\"地方\",Snapshot-C\nhttp://host/c.m3u8\n"

        const val EXISTING = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-id=\"X\" group-title=\"我的\",Mine-A\nhttp://mine/a.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"Y\" group-title=\"我的\",Mine-B\nhttp://mine/b.m3u8\n"

        /** No `#EXTINF` rows and no URLs: the parser yields zero channels from this. */
        const val GARBAGE = "#EXTM3U\nnot a playlist at all\n"
    }

    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
}
