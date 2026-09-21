package ilab.iptv.player.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import ilab.iptv.player.core.data.catalog.CatalogLoadReport
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.repository.InMemoryChannelRepository
import ilab.iptv.player.core.data.repository.InMemoryStreamRepository
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.StreamOutcome
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The P2-1 claim, stated as tests: the Room-backed ports behave like the in-memory ones they replace,
 * survive a restart, and write through the batches and transactions docs/02 §4.5 C4 asks for.
 *
 * The equivalence tests run **both** implementations over the same 658-channel fixture and compare
 * them channel by channel. That is the only way "drop-in replacement" is worth anything: two
 * implementations that merely look similar drift on the first filter edge case.
 */
@RunWith(AndroidJUnit4::class)
class RoomRepositoryTest {

    private lateinit var database: IptvDatabase
    private lateinit var rig: RoomFixtures.Rig

    private val all = ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null)

    @Before
    fun setUp() {
        RoomFixtures.deleteDatabase(COLD_START_DB)
        database = RoomFixtures.inMemoryDatabase()
        rig = RoomFixtures.Rig(database)
    }

    @After
    fun tearDown() {
        database.close()
        RoomFixtures.deleteDatabase(COLD_START_DB)
    }

    // ---- seeding ----

    @Test
    fun `seeding loads the bundled fixture into Room once`() = test {
        val seeded = rig.seeder.ensureSeeded()
        val again = rig.seeder.ensureSeeded()

        assertThat(seeded).isTrue()
        assertThat(again).isFalse()
        assertThat(database.channelDao().count()).isEqualTo(658)
        assertThat(database.streamDao().countByChannel().sumOf { it.count }).isGreaterThan(0)
        assertThat(database.channelDao().countByGroupKey()).isNotEmpty()
        assertThat(rig.logger.codes).contains(EventCodes.DB_UPSERT)
        assertThat(rig.logger.codes).contains(EventCodes.SRC_PARSE_OK)
    }

    @Test
    fun `the seeded row count matches the parse report`() = test {
        // TESTABLE-1 split parse() into prepare()/commit(); the test needs the mapped half only.
        val parsed = rig.catalog.prepare(Fixtures.text(Fixtures.BASELINE_PLAYLIST), "p1-2-fixture")
        rig.writer.write(parsed.mapped, nowMs = 1L)

        assertThat(database.channelDao().count()).isEqualTo(parsed.report.channels)
        assertThat(database.streamDao().countByChannel().sumOf { it.count }).isEqualTo(parsed.report.streams)
        // The fixture's group mix survives the round trip: docs/02 §5.1's group_key must not collapse
        // the five classifications into one.
        val groups = rig.channels.countByGroup()
        assertThat(groups.keys).containsAtLeast(ChannelGroup.CCTV, ChannelGroup.SATELLITE, ChannelGroup.LOCAL)
    }

    @Test
    fun `writing the same catalog twice does not duplicate rows`() = test {
        val parsed = rig.catalog.prepare(Fixtures.text(Fixtures.BASELINE_PLAYLIST), "p1-2-fixture")

        rig.writer.write(parsed.mapped, nowMs = 1L)
        rig.writer.write(parsed.mapped, nowMs = 2L)

        assertThat(database.channelDao().count()).isEqualTo(parsed.report.channels)
        assertThat(database.streamDao().countByChannel().sumOf { it.count }).isEqualTo(parsed.report.streams)
    }

    // ---- drop-in equivalence ----

    @Test
    fun `observe returns the same channels in the same order as the in-memory repository`() = test {
        val inMemory = inMemoryRepository()

        val roomList = rig.channels.observe(all).first()
        val memoryList = inMemory.observe(all).first()

        assertThat(roomList).hasSize(658)
        assertThat(roomList.map { it.channel.nameKey }).isEqualTo(memoryList.map { it.channel.nameKey })
        assertThat(roomList.map { it.channel.groupKey }).isEqualTo(memoryList.map { it.channel.groupKey })
        assertThat(roomList.map { it.channel.channelNo }).isEqualTo(memoryList.map { it.channel.channelNo })
        assertThat(roomList.map { it.channel.group }).isEqualTo(memoryList.map { it.channel.group })
    }

    @Test
    fun `each channel's streams are ordered the same way in both implementations`() = test {
        val inMemory = inMemoryRepository()

        val room = rig.channels.observe(all).first().associateBy { it.channel.nameKey to it.channel.groupKey }
        val memory = inMemory.observe(all).first().associateBy { it.channel.nameKey to it.channel.groupKey }

        val withStreams = room.values.filter { it.streams.isNotEmpty() }.take(25)
        assertThat(withStreams).isNotEmpty()
        withStreams.forEach { item ->
            val other = memory.getValue(item.channel.nameKey to item.channel.groupKey)
            assertThat(item.streams.map { it.urlHash }).isEqualTo(other.streams.map { it.urlHash })
            assertThat(item.channel.streamCount).isEqualTo(other.channel.streamCount)
        }
    }

    @Test
    fun `the group filter matches the in-memory repository`() = test {
        val inMemory = inMemoryRepository()
        val filter = all.copy(group = ChannelGroup.HK_MO_TW)

        val room = rig.channels.observe(filter).first()
        val memory = inMemory.observe(filter).first()

        assertThat(room.map { it.channel.nameKey }).isEqualTo(memory.map { it.channel.nameKey })
        assertThat(room).isNotEmpty()
    }

    @Test
    fun `the free-text filter matches the in-memory repository`() = test {
        val inMemory = inMemoryRepository()
        val filter = all.copy(query = "CCTV")

        val room = rig.channels.observe(filter).first()
        val memory = inMemory.observe(filter).first()

        assertThat(room.map { it.channel.nameKey }).isEqualTo(memory.map { it.channel.nameKey })
        assertThat(room).isNotEmpty()
    }

    @Test
    fun `favourites-only and hidden filters are applied`() = test {
        val channels = rig.channels.observe(all).first().map { it.channel }
        val favourite = channels[0]
        val hidden = channels[1]
        rig.channels.setFavorite(favourite.id, true)
        rig.channels.setHidden(hidden.id, true)

        assertThat(rig.channels.observe(all.copy(favoritesOnly = true)).first().map { it.channel.id })
            .containsExactly(favourite.id)
        // The hidden channel drops out of the default list, including its favourite view.
        assertThat(rig.channels.observe(all).first().map { it.channel.id }).doesNotContain(hidden.id)
        assertThat(rig.channels.observe(all.copy(includeHidden = true)).first().map { it.channel.id })
            .contains(hidden.id)
        assertThat(
            rig.channels.observe(all.copy(favoritesOnly = true, includeHidden = true)).first()
                .map { it.channel.id },
        ).containsExactly(favourite.id)
    }

    @Test
    fun `get returns the same channel-and-streams pair as the in-memory repository`() = test {
        val seeded = rig.channels.observe(all).first().first().channel
        val inMemory = inMemoryRepository()

        val room = rig.channels.get(seeded.id)!!
        val memoryItem = inMemory.observe(all).first().first { it.channel.nameKey == seeded.nameKey }
        val memory = inMemory.get(memoryItem.channel.id)!!

        assertThat(room.channel.name).isEqualTo(memory.channel.name)
        assertThat(room.streams.map { it.url }).isEqualTo(memory.streams.map { it.url })
    }

    // ---- mutations ----

    @Test
    fun `reorder rewrites sort order inside the channel's own group`() = test {
        val before = rig.channels.observe(all).first()
        val groupKey = before.first().channel.groupKey
        val section = before.filter { it.channel.groupKey == groupKey }
        val moved = section[2].channel

        rig.channels.reorder(moved.id, 0)

        // The stored contract is the rewritten `sort_order`: gapless, zero-based, and the moved
        // channel at the requested index. The *displayed* order is a domain rule that ranks
        // `channel_no` above `sort_order` (D12), so it is deliberately not asserted here.
        val after = database.channelDao().byGroup(groupKey)
        assertThat(after.single { it.id == moved.id }.sortOrder).isEqualTo(0)
        assertThat(after.map { it.sortOrder }.sorted()).isEqualTo((0 until after.size).toList())
    }

    @Test
    fun `reorder is idempotent when repeated`() = test {
        val section = rig.channels.observe(all).first()
        val target = section[3].channel
        val groupKey = target.groupKey

        rig.channels.reorder(target.id, 1)
        val once = database.channelDao().byGroup(groupKey).associate { it.id to it.sortOrder }
        rig.channels.reorder(target.id, 1)
        val twice = database.channelDao().byGroup(groupKey).associate { it.id to it.sortOrder }

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `channel number and EPG binding round trip through the database`() = test {
        val channel = rig.channels.observe(all).first().first().channel

        rig.channels.setChannelNo(channel.id, 501)
        rig.channels.setEpgBinding(channel.id, "epg.501", EpgMatchType.MANUAL)

        val reloaded = rig.channels.get(channel.id)!!.channel
        assertThat(reloaded.channelNo).isEqualTo(501)
        assertThat(reloaded.epgChannelId).isEqualTo("epg.501")
        assertThat(reloaded.epgMatch).isEqualTo(EpgMatchType.MANUAL)
    }

    // ---- streams ----

    @Test
    fun `candidates come back in the 4_3 order`() = test {
        val channel = rig.channels.observe(all).first().first { it.streams.size >= 2 }.channel

        val candidates = rig.streams.candidates(channel.id)

        assertThat(candidates.map { it.id }).isEqualTo(
            candidates.sortedWith(
                compareByDescending<ilab.iptv.player.core.model.Stream> { it.score }
                    .thenBy { it.priority }
                    .thenByDescending { it.lastOkAtMs ?: Long.MIN_VALUE }
                    .thenBy { it.id },
            ).map { it.id },
        )
    }

    @Test
    fun `recordOutcome writes the stream columns and one history row`() = test {
        val stream = rig.channels.observe(all).first().first().streams.first()

        rig.streams.recordOutcome(stream.id, StreamOutcome(stream.id, ok = false, atMs = 5_000L, detail = "TIMEOUT"))
        val health = rig.streams.health(stream.id)
        val row = database.streamDao().getById(stream.id)!!

        assertThat(row.lastCheckAt).isEqualTo(5_000L)
        assertThat(row.failCount).isEqualTo(1)
        assertThat(row.lastError).isEqualTo("TIMEOUT")
        assertThat(health.attempts).isEqualTo(1)
        assertThat(health.failures).isEqualTo(1)
        assertThat(health.consecutiveFails).isEqualTo(1)
        assertThat(database.playHistoryDao().count()).isEqualTo(1)
    }

    @Test
    fun `a successful outcome resets the failure count and stamps the OK time`() = test {
        val stream = rig.channels.observe(all).first().first().streams.first()

        rig.streams.recordOutcome(stream.id, StreamOutcome(stream.id, ok = false, atMs = 1_000L, failure = FailureClass.TIMEOUT))
        rig.streams.recordOutcome(stream.id, StreamOutcome(stream.id, ok = true, atMs = 2_000L))

        val row = database.streamDao().getById(stream.id)!!
        val health = rig.streams.health(stream.id)
        assertThat(row.lastOkAt).isEqualTo(2_000L)
        assertThat(row.failCount).isEqualTo(0)
        assertThat(health.attempts).isEqualTo(2)
        assertThat(health.failures).isEqualTo(1)
        assertThat(health.lastOkAtMs).isEqualTo(2_000L)
    }

    @Test
    fun `markStale clears the check stamp of the old streams`() = test {
        val stream = rig.channels.observe(all).first().first().streams.first()
        rig.streams.recordOutcome(stream.id, StreamOutcome(stream.id, ok = true, atMs = 1_000L))

        val cleared = rig.streams.markStale(beforeMs = 2_000L)

        assertThat(cleared).isAtLeast(1)
        assertThat(database.streamDao().getById(stream.id)!!.lastCheckAt).isNull()
    }

    // ---- cold start ----

    @Test
    fun `a cold start reads the catalog from the database without re-parsing the fixture`() = test {
        val first = RoomFixtures.Rig(RoomFixtures.fileDatabase(COLD_START_DB))
        assertThat(first.seeder.ensureSeeded()).isTrue()
        val favourite = first.channels.observe(all).first().first().channel
        first.channels.setFavorite(favourite.id, true)
        assertThat(first.logger.codes).contains(EventCodes.DB_UPSERT)
        first.database.close()

        // Second process: a brand new database handle, the same file.
        val second = RoomFixtures.Rig(RoomFixtures.fileDatabase(COLD_START_DB))
        val seededAgain = second.seeder.ensureSeeded()
        val channels = second.channels.observe(all).first()

        assertThat(seededAgain).isFalse() // already in SQLite: nothing is parsed this time
        assertThat(second.logger.codes).doesNotContain(EventCodes.DB_UPSERT)
        assertThat(channels).hasSize(658)
        // User state made it across the restart, which is the whole point of the card.
        assertThat(second.channels.observe(all.copy(favoritesOnly = true)).first().map { it.channel.id })
            .containsExactly(favourite.id)
        second.database.close()
    }

    private fun inMemoryRepository(): InMemoryChannelRepository {
        val store = ChannelStore()
        val catalog = ChannelCatalog(store, FakeClock())
        return InMemoryChannelRepository(store, FakeBootstrapper(catalog, Fixtures.text(Fixtures.BASELINE_PLAYLIST)))
    }

    private class FakeBootstrapper(
        private val catalog: ChannelCatalog,
        private val text: String,
    ) : CatalogBootstrapper {
        private var cached: CatalogLoadReport? = null

        override suspend fun ensureLoaded(): CatalogLoadReport? =
            cached ?: catalog.load(text, sourceId = "test-fixture").also { cached = it }
    }

    private companion object {
        const val COLD_START_DB = "p2-1-cold-start.db"
    }
}
