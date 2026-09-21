package ilab.iptv.player.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.database.ProgrammeWindows.KEEP_FUTURE_MS
import ilab.iptv.player.core.database.ProgrammeWindows.KEEP_PAST_MS
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `programme`: idempotent slots, the grid window, now/next and §5.1's retention rule.
 */
@RunWith(AndroidJUnit4::class)
class ProgrammeDaoTest {

    private lateinit var database: IptvDatabase
    private val dao get() = database.programmeDao()

    @Before
    fun setUp() {
        database = DatabaseFixtures.inMemory()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `UNIQUE(epg_channel_id, start_ms) makes the same slot idempotent`() = test {
        val slot = DatabaseFixtures.programme(startMs = 1_000L, title = "old")

        dao.replaceBatch(listOf(slot))
        dao.replaceBatch(listOf(slot.copy(title = "new")))

        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.countForChannel("cctv1")).isEqualTo(1)
        assertThat(dao.window(listOf("cctv1"), 0L, 10_000L, 10).single().title).isEqualTo("new")
    }

    @Test
    fun `the same start_ms on two EPG channels is two rows`() = test {
        dao.replaceBatch(
            listOf(
                DatabaseFixtures.programme(epgChannelId = "cctv1", startMs = 1_000L),
                DatabaseFixtures.programme(epgChannelId = "cctv2", startMs = 1_000L),
            ),
        )

        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.window(listOf("cctv1"), 0L, 10_000L, 10)).hasSize(1)
        assertThat(dao.window(listOf("cctv1", "cctv2"), 0L, 10_000L, 10)).hasSize(2)
    }

    @Test
    fun `the window query keeps a programme that is still running at the left edge`() = test {
        dao.replaceBatch(
            listOf(
                DatabaseFixtures.programme(startMs = 0L, stopMs = 3_000L, title = "running"),
                DatabaseFixtures.programme(startMs = 10_000L, stopMs = 12_000L, title = "later"),
                DatabaseFixtures.programme(startMs = 20_000L, stopMs = 21_000L, title = "too late"),
            ),
        )

        val window = dao.window(listOf("cctv1"), fromMs = 2_000L, toMs = 15_000L, limit = 10)

        assertThat(window.map { it.title }).containsExactly("running", "later").inOrder()
    }

    @Test
    fun `the window query honours its limit`() = test {
        dao.replaceBatch((0 until 10).map { DatabaseFixtures.programme(startMs = it * 1_000L) })

        assertThat(dao.window(listOf("cctv1"), 0L, 100_000L, limit = 4)).hasSize(4)
    }

    @Test
    fun `now and next pick the covering and the following slot`() = test {
        dao.replaceBatch(
            listOf(
                DatabaseFixtures.programme(startMs = 0L, stopMs = 1_000L, title = "past"),
                DatabaseFixtures.programme(startMs = 1_000L, stopMs = 2_000L, title = "now"),
                DatabaseFixtures.programme(startMs = 2_000L, stopMs = 3_000L, title = "next"),
            ),
        )

        assertThat(dao.now("cctv1", 1_500L)!!.title).isEqualTo("now")
        assertThat(dao.next("cctv1", 1_500L)!!.title).isEqualTo("next")
        assertThat(dao.now("cctv1", 9_999L)).isNull()
        assertThat(dao.next("cctv1", 9_999L)).isNull()
    }

    @Test
    fun `pruneWindow drops what is outside the window and keeps what is inside`() = test {
        val now = 1_000_000L
        val window = ProgrammeWindows.around(now)
        dao.replaceBatch(
            listOf(
                DatabaseFixtures.programme(startMs = window.fromMs - 10_000L, stopMs = window.fromMs - 5_000L),
                DatabaseFixtures.programme(startMs = window.fromMs + 1_000L, stopMs = window.fromMs + 2_000L),
                DatabaseFixtures.programme(startMs = window.toMs - 1_000L, stopMs = window.toMs + 1_000L),
                DatabaseFixtures.programme(startMs = window.toMs + 10_000L, stopMs = window.toMs + 11_000L),
            ),
        )

        val deleted = dao.pruneWindow(listOf("cctv1"), window.fromMs, window.toMs)

        assertThat(deleted).isEqualTo(2)
        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.window(listOf("cctv1"), Long.MIN_VALUE, Long.MAX_VALUE, 10)).hasSize(2)
    }

    @Test
    fun `pruneAround chunks a long channel list instead of building one huge IN clause`() = test {
        val now = 500_000L
        val window = ProgrammeWindows.around(now)
        val channels = (1..7).map { "ch$it" }
        dao.replaceBatch(
            channels.flatMap { channel ->
                listOf(
                    DatabaseFixtures.programme(
                        epgChannelId = channel,
                        startMs = window.fromMs - 60_000L,
                        stopMs = window.fromMs - 30_000L,
                    ),
                    DatabaseFixtures.programme(
                        epgChannelId = channel,
                        startMs = window.fromMs + 1_000L,
                        stopMs = window.fromMs + 2_000L,
                    ),
                )
            },
        )

        val deleted = dao.pruneAround(now, channels, batchSize = 3)

        assertThat(deleted).isEqualTo(channels.size)
        assertThat(dao.count()).isEqualTo(channels.size)
    }

    @Test
    fun `the window constant is the documented six hours back and forty eight forward`() {
        val window = ProgrammeWindows.around(10_000_000L)
        assertThat(KEEP_PAST_MS).isEqualTo(6L * 60 * 60 * 1000)
        assertThat(KEEP_FUTURE_MS).isEqualTo(48L * 60 * 60 * 1000)
        assertThat(window.fromMs).isEqualTo(10_000_000L - KEEP_PAST_MS)
        assertThat(window.toMs).isEqualTo(10_000_000L + KEEP_FUTURE_MS)
    }

    @Test
    fun `coverage counts distinct EPG channels`() = test {
        dao.replaceBatch(
            listOf(
                DatabaseFixtures.programme(epgChannelId = "cctv1", startMs = 0L),
                DatabaseFixtures.programme(epgChannelId = "cctv1", startMs = 1_000L),
                DatabaseFixtures.programme(epgChannelId = "cctv2", startMs = 0L),
            ),
        )

        assertThat(dao.channelCount()).isEqualTo(2)
        assertThat(dao.countByChannel().associate { it.epgChannelId to it.count })
            .containsExactly("cctv1", 2, "cctv2", 1)
    }
}
