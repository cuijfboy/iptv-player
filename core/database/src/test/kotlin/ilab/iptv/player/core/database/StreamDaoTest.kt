package ilab.iptv.player.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `stream` behaviour: the `UNIQUE(channel_id, url_hash)` identity, the selection order the fail-over
 * path reads, the health columns and the stale probe.
 */
@RunWith(AndroidJUnit4::class)
class StreamDaoTest {

    private lateinit var database: IptvDatabase
    private val channels get() = database.channelDao()
    private val streams get() = database.streamDao()
    private val history get() = database.playHistoryDao()

    private var channelId: Long = 0

    @Before
    fun setUp() {
        database = DatabaseFixtures.inMemory()
        channelId = runBlockingInsert()
    }

    private fun runBlockingInsert(): Long = kotlinx.coroutines.runBlocking {
        channels.insert(DatabaseFixtures.channel(name = "A", nameKey = "a", groupKey = "g"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `candidates follow the 4_3 order and skip disabled streams`() = test {
        streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1", score = 10, priority = 1))
        streams.insert(DatabaseFixtures.stream(channelId, url = "u2", urlHash = "h2", score = 10, priority = 0))
        streams.insert(DatabaseFixtures.stream(channelId, url = "u3", urlHash = "h3", score = 30, priority = 5))
        streams.insert(
            DatabaseFixtures.stream(channelId, url = "u4", urlHash = "h4", score = 99, disabled = true),
        )

        val ordered = streams.candidates(channelId)

        // score desc, then priority asc; the disabled stream never appears even with the best score.
        assertThat(ordered.map { it.urlHash }).containsExactly("h3", "h2", "h1").inOrder()
    }

    @Test
    fun `UNIQUE(channel_id, url_hash) rejects the same URL twice for one channel`() = test {
        streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1"))

        val failure = runCatching {
            streams.insert(DatabaseFixtures.stream(channelId, url = "u1-again", urlHash = "h1"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(streams.countForChannel(channelId)).isEqualTo(1)
    }

    @Test
    fun `W4 - the same URL may serve two channels`() = test {
        val other = channels.insert(DatabaseFixtures.channel(name = "B", nameKey = "b", groupKey = "g"))
        streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1"))
        streams.insert(DatabaseFixtures.stream(other, url = "u1", urlHash = "h1"))

        assertThat(streams.countForChannel(channelId)).isEqualTo(1)
        assertThat(streams.countForChannel(other)).isEqualTo(1)
    }

    @Test
    fun `a stream without its channel is rejected by the foreign key`() = test {
        val failure = runCatching {
            streams.insert(DatabaseFixtures.stream(channelId = 9_999L, url = "u1", urlHash = "h1"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun `upsertAll is idempotent and updates in place`() = test {
        val first = streams.upsertAll(
            listOf(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1", score = 1)),
        )
        val idBefore = streams.findIdByChannelAndHash(channelId, "h1")!!

        val second = streams.upsertAll(
            listOf(DatabaseFixtures.stream(channelId, url = "u1-v2", urlHash = "h1", score = 8, priority = 2)),
        )

        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(1)
        assertThat(streams.findIdByChannelAndHash(channelId, "h1")).isEqualTo(idBefore)
        val row = streams.getById(idBefore)!!
        assertThat(row.score).isEqualTo(8)
        assertThat(row.priority).isEqualTo(2)
        assertThat(streams.countForChannel(channelId)).isEqualTo(1)
    }

    @Test
    fun `upsertAll writes a batch larger than one transaction`() = test {
        val batch = (1..1_200).map {
            DatabaseFixtures.stream(channelId, url = "u$it", urlHash = "h$it")
        }

        val written = batch.chunked(500).sumOf { streams.upsertAll(it) }

        assertThat(written).isEqualTo(1_200)
        assertThat(streams.countForChannel(channelId)).isEqualTo(1_200)
        assertThat(streams.countByChannel().single().count).isEqualTo(1_200)
    }

    @Test
    fun `recordOutcome moves the OK stamp and resets the fail count on success`() = test {
        val id = streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1", failCount = 4))

        streams.recordOutcome(id, ok = true, atMs = 500L, lastError = null)

        val row = streams.getById(id)!!
        assertThat(row.lastOkAt).isEqualTo(500L)
        assertThat(row.lastCheckAt).isEqualTo(500L)
        assertThat(row.failCount).isEqualTo(0)
        assertThat(row.lastError).isNull()
    }

    @Test
    fun `recordOutcome counts a failure and keeps the last OK stamp`() = test {
        val id = streams.insert(
            DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1", lastOkAt = 100L, failCount = 1),
        )

        streams.recordOutcome(id, ok = false, atMs = 900L, lastError = "TIMEOUT")
        streams.recordOutcome(id, ok = false, atMs = 901L, lastError = "TIMEOUT")

        val row = streams.getById(id)!!
        assertThat(row.lastOkAt).isEqualTo(100L)
        assertThat(row.lastCheckAt).isEqualTo(901L)
        assertThat(row.failCount).isEqualTo(3)
        assertThat(row.lastError).isEqualTo("TIMEOUT")
    }

    @Test
    fun `markStale only clears the stamp of streams checked before the cut-off`() = test {
        val old = streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1", lastCheckAt = 10L))
        val fresh = streams.insert(DatabaseFixtures.stream(channelId, url = "u2", urlHash = "h2", lastCheckAt = 900L))
        val never = streams.insert(DatabaseFixtures.stream(channelId, url = "u3", urlHash = "h3", lastCheckAt = null))

        val cleared = streams.markStale(beforeMs = 500L)

        assertThat(cleared).isEqualTo(1)
        assertThat(streams.getById(old)!!.lastCheckAt).isNull()
        assertThat(streams.getById(fresh)!!.lastCheckAt).isEqualTo(900L)
        assertThat(streams.getById(never)!!.lastCheckAt).isNull()
    }

    @Test
    fun `health is derived from play_history plus the stream's own columns`() = test {
        val id = streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1", lastOkAt = 700L, failCount = 2))
        history.insert(DatabaseFixtures.history(streamId = id, channelId = channelId, result = "OK", atMs = 100L))
        history.insert(DatabaseFixtures.history(streamId = id, channelId = channelId, result = "TIMEOUT", atMs = 200L))
        history.insert(DatabaseFixtures.history(streamId = id, channelId = channelId, result = "OK", atMs = 300L))

        val counts = streams.healthCounts(id)!!

        assertThat(counts.attempts).isEqualTo(3)
        assertThat(counts.failures).isEqualTo(1)
        assertThat(streams.lastOkAt(id)).isEqualTo(700L)
        assertThat(streams.consecutiveFails(id)).isEqualTo(2)
    }

    @Test
    fun `health of a stream with no history reports zero attempts, not a crash`() = test {
        val id = streams.insert(DatabaseFixtures.stream(channelId, url = "u1", urlHash = "h1"))

        val counts = streams.healthCounts(id)!!

        assertThat(counts.attempts).isEqualTo(0)
        // SQLite's SUM() over an empty set is NULL: the contract is 0, and the wrong answer on the
        // happy path is exactly the kind of thing "no rows" hides, so it is asserted here.
        assertThat(counts.failures).isNull()
    }
}
