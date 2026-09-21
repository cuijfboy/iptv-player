package ilab.iptv.player.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.database.dao.ChannelOrder
import ilab.iptv.player.core.database.dao.ChannelWithStreamRows
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `channel` round trips, the §5.1 unique index and the idempotent upsert. Every assertion is about a
 * rule the docs fix, not about Room's behaviour in general.
 */
@RunWith(AndroidJUnit4::class)
class ChannelDaoTest {

    private lateinit var database: IptvDatabase
    private val dao get() = database.channelDao()

    @Before
    fun setUp() {
        database = DatabaseFixtures.inMemory()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `round trip preserves every column of the 5_1 schema`() = test {
        val id = dao.insert(
            DatabaseFixtures.channel(
                name = "CCTV-1 综合",
                nameKey = "cctv-1 综合",
                groupKey = "cctv",
                groupTitle = "央视",
                channelNo = 1,
                favorite = true,
                hidden = false,
                sortOrder = 3,
                epgChannelId = "cctv1.example",
                epgMatch = "TVG_ID",
                createdAt = 111L,
                updatedAt = 222L,
            ),
        )

        val row = dao.getWithStreams(id)!!.channel
        assertThat(row.name).isEqualTo("CCTV-1 综合")
        assertThat(row.nameKey).isEqualTo("cctv-1 综合")
        assertThat(row.groupKey).isEqualTo("cctv")
        assertThat(row.groupTitle).isEqualTo("央视")
        assertThat(row.channelNo).isEqualTo(1)
        assertThat(row.favorite).isTrue()
        assertThat(row.hidden).isFalse()
        assertThat(row.sortOrder).isEqualTo(3)
        assertThat(row.epgChannelId).isEqualTo("cctv1.example")
        assertThat(row.epgMatch).isEqualTo("TVG_ID")
        assertThat(row.createdAt).isEqualTo(111L)
        assertThat(row.updatedAt).isEqualTo(222L)
    }

    @Test
    fun `UNIQUE(name_key, group_key) rejects a duplicate name in the same group`() = test {
        dao.insert(DatabaseFixtures.channel(name = "CCTV-1", nameKey = "cctv-1", groupKey = "cctv"))

        val failure = runCatching {
            dao.insert(DatabaseFixtures.channel(name = "CCTV1", nameKey = "cctv-1", groupKey = "cctv"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `W4 - the same name in two groups is two rows`() = test {
        dao.insert(DatabaseFixtures.channel(name = "CCTV-1", nameKey = "cctv-1", groupKey = "cctv"))
        dao.insert(DatabaseFixtures.channel(name = "CCTV-1", nameKey = "cctv-1", groupKey = "地方/其他"))

        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.byGroup("cctv")).hasSize(1)
        assertThat(dao.byGroup("地方/其他")).hasSize(1)
    }

    @Test
    fun `upsertAll is idempotent and keeps ids, favourites and created_at`() = test {
        val first = dao.upsertAll(listOf(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "g")))
        dao.setFavorite(first.single(), favorite = true, updatedAt = 5L)

        val second = dao.upsertAll(
            listOf(
                DatabaseFixtures.channel(
                    name = "A renamed",
                    nameKey = "a",
                    groupKey = "g",
                    createdAt = 9_999L,
                    updatedAt = 7_777L,
                ),
            ),
        )

        assertThat(second).isEqualTo(first)
        val row = dao.findByKey("a", "g")!!
        assertThat(row.name).isEqualTo("A renamed")
        assertThat(row.createdAt).isEqualTo(1_000L) // created_at is the original, not the incoming one
        assertThat(row.updatedAt).isEqualTo(7_777L)
        assertThat(row.favorite).isTrue() // a refresh does not clear what the user set
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `upsertAll takes source-owned columns and keeps user-owned ones`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "g", channelNo = null))
        dao.setFavorite(id, true, 5L)
        dao.setHidden(id, true, 6L)
        dao.setChannelNo(id, 7, 7L)
        dao.setEpgBinding(id, "epg.a", "MANUAL", 8L)

        dao.upsertAll(
            listOf(
                DatabaseFixtures.channel(
                    name = "A relabelled by the source",
                    nameKey = "a",
                    groupKey = "g",
                    channelNo = 99,
                    sortOrder = 3,
                    epgMatch = "TVG_ID",
                ),
            ),
        )

        val row = dao.findByKey("a", "g")!!
        // Source-owned: the refresh wins.
        assertThat(row.name).isEqualTo("A relabelled by the source")
        // User-owned: the refresh must not undo any of these (docs/01 F5).
        assertThat(row.favorite).isTrue()
        assertThat(row.hidden).isTrue()
        assertThat(row.channelNo).isEqualTo(7)
        assertThat(row.epgChannelId).isEqualTo("epg.a")
        assertThat(row.epgMatch).isEqualTo("MANUAL")
        assertThat(row.sortOrder).isEqualTo(0)
    }

    @Test
    fun `the source's channel number lands while the column is still empty`() = test {
        dao.upsertAll(
            listOf(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "g", channelNo = null)),
        )

        dao.upsertAll(
            listOf(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "g", channelNo = 12)),
        )

        assertThat(dao.findByKey("a", "g")!!.channelNo).isEqualTo(12)
    }

    @Test
    fun `countByGroupKey groups without reading the rows`() = test {
        dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv"))
        dao.insert(DatabaseFixtures.channel(name = "B", nameKey = "b", groupKey = "cctv"))
        dao.insert(DatabaseFixtures.channel(name = "C", nameKey = "c", groupKey = "地方/其他"))

        val counts = dao.countByGroupKey().associate { it.groupKey to it.count }
        assertThat(counts).containsExactly("cctv", 2, "地方/其他", 1)
    }

    @Test
    fun `channel number lookup and the byGroup read both work`() = test {
        dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv", channelNo = 5))
        dao.insert(DatabaseFixtures.channel(name = "B", nameKey = "b", groupKey = "cctv", channelNo = 1))

        assertThat(dao.byChannelNo(5)!!.name).isEqualTo("A")
        assertThat(dao.byChannelNo(99)).isNull()
        // sort_order is equal for both rows, so the tie is broken by id (A was inserted first).
        assertThat(dao.byGroup("cctv").map { it.name }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `the browse mutations write back and favourites and hidden are queryable`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv"))

        dao.setFavorite(id, true, 10L)
        dao.setEpgBinding(id, "epg.a", "MANUAL", 11L)
        dao.setChannelNo(id, 42, 12L)
        dao.setHidden(id, true, 13L)

        assertThat(dao.favorites().map { it.id }).containsExactly(id)
        assertThat(dao.hidden().map { it.id }).containsExactly(id)
        val row = dao.findByKey("a", "cctv")!!
        assertThat(row.epgChannelId).isEqualTo("epg.a")
        assertThat(row.epgMatch).isEqualTo("MANUAL")
        assertThat(row.channelNo).isEqualTo(42)
        assertThat(row.updatedAt).isEqualTo(13L)
    }

    @Test
    fun `setChannelNo accepts null so a user edit can be cleared`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv", channelNo = 7))

        dao.setChannelNo(id, null, 10L)

        assertThat(dao.findByKey("a", "cctv")!!.channelNo).isNull()
    }

    @Test
    fun `reorder rewrites the whole section's sort_order in one call`() = test {
        val a = dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv", sortOrder = 0))
        val b = dao.insert(DatabaseFixtures.channel(name = "B", nameKey = "b", groupKey = "cctv", sortOrder = 1))
        val c = dao.insert(DatabaseFixtures.channel(name = "C", nameKey = "c", groupKey = "cctv", sortOrder = 2))

        dao.setSortOrders(listOf(ChannelOrder(b, 0), ChannelOrder(c, 1), ChannelOrder(a, 2)), 99L)

        val ordered = dao.byGroup("cctv")
        assertThat(ordered.map { it.name }).containsExactly("B", "C", "A").inOrder()
        assertThat(ordered.map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()
        assertThat(ordered.all { it.updatedAt == 99L }).isTrue()
    }

    @Test
    fun `deleting a channel cascades to its streams`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv"))
        database.streamDao().insert(DatabaseFixtures.stream(channelId = id, urlHash = "h1"))
        database.streamDao().insert(DatabaseFixtures.stream(channelId = id, url = "u2", urlHash = "h2"))

        dao.deleteById(id)

        assertThat(database.streamDao().countForChannel(id)).isEqualTo(0)
    }

    @Test
    fun `getWithStreams reads the channel and its streams in one transaction`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "cctv"))
        database.streamDao().insert(DatabaseFixtures.stream(channelId = id, urlHash = "h1"))
        database.streamDao().insert(DatabaseFixtures.stream(channelId = id, url = "u2", urlHash = "h2"))

        val row: ChannelWithStreamRows = dao.getWithStreams(id)!!

        assertThat(row.channel.id).isEqualTo(id)
        assertThat(row.streams).hasSize(2)
    }
}
