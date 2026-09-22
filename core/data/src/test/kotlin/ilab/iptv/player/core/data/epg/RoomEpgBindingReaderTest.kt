package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.dao.EpgBinding
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.model.EpgGridWindow
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.core.source.normalize.Keys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG-20260922-018's evidence entry: one row per channel with the id it is bound to, the tier that
 * bound it and how many programmes that id holds **inside the grid window**.
 *
 * The three things this file has to pin, because they are exactly the three ways a reader could be
 * misled on the TV:
 *
 * - a channel whose guide id holds something in the window reads back with a count > 0 — "the number
 *   says covered" and "the row has a block" are the same statement;
 * - a channel bound to an id that is empty **in the window** reads back as bound with count 0, never
 *   as unbound and never with a retention-window count (that混淆 is what BUG-018 was);
 * - a channel with no guide id at all reads back with `null` and `NONE`, so "nothing bound" and
 *   "bound and blank" stay distinguishable.
 */
@RunWith(AndroidJUnit4::class)
class RoomEpgBindingReaderTest {

    private lateinit var database: IptvDatabase
    private lateinit var clock: Clock
    private lateinit var repository: RoomEpgRepository
    private lateinit var reader: RoomEpgBindingReader

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = RoomFixtures.inMemoryDatabase()
        clock = RoomFixtures.clock(now)
        repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), clock)
        reader = RoomEpgBindingReader(database.channelDao(), database.programmeDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `every channel comes back with its binding, tier and window count`() = runBlocking<Unit> {
        val id = bound("正在播", "正在播.cn", EpgMatchType.NAME_EXACT)
        val empty = bound("空白", "空白.cn", EpgMatchType.ALIAS)
        val unbound = database.channelDao().insert(fixture("没有绑定"))
        repository.replaceAll("正在播.cn", listOf(row("正在播.cn", now, now + 30 * 60_000L)))

        val report = reader.bindingReport()

        assertThat(report.window).isEqualTo(EpgGridWindow.of(now))
        assertThat(report.total).isEqualTo(3)
        assertThat(report.matched).isEqualTo(2)
        assertThat(report.withProgrammes).isEqualTo(1)
        assertThat(report.emptyBinding).isEqualTo(1)

        val playing = report.rows.single { it.channelId == id }
        assertThat(playing.epgChannelId).isEqualTo("正在播.cn")
        assertThat(playing.matchedBy).isEqualTo(EpgMatchType.NAME_EXACT)
        assertThat(playing.programmesInWindow).isEqualTo(1)
        assertThat(playing.emptyBinding).isFalse()

        // Bound, but the id holds nothing *here*: the row is blank on screen while the id-side
        // coverage would have called it covered (the BUG-018 shape).
        val blank = report.rows.single { it.channelId == empty }
        assertThat(blank.bound).isTrue()
        assertThat(blank.programmesInWindow).isEqualTo(0)
        assertThat(blank.emptyBinding).isTrue()

        val none = report.rows.single { it.channelId == unbound }
        assertThat(none.epgChannelId).isNull()
        assertThat(none.matchedBy).isEqualTo(EpgMatchType.NONE)
        assertThat(none.programmesInWindow).isEqualTo(0)
        assertThat(none.bound).isFalse()
    }

    @Test
    fun `a programme outside the grid window does not count, even though the table keeps it`() =
        runBlocking<Unit> {
            val id = bound("明晚", "明晚.cn", EpgMatchType.NAME_EXACT)
            // 20 h out: inside the `[now-6h, now+48h]` retention window, outside the grid's six hours.
            repository.replaceAll("明晚.cn", listOf(row("明晚.cn", now + 20 * 3_600_000L, now + 21 * 3_600_000L)))

            val report = reader.bindingReport()

            val row = report.rows.single { it.channelId == id }
            assertThat(row.programmesInWindow).isEqualTo(0)
            assertThat(report.withProgrammes).isEqualTo(0)
            assertThat(report.emptyBinding).isEqualTo(1)
            assertThat(database.programmeDao().countForChannel("明晚.cn")).isEqualTo(1)
        }

    @Test
    fun `a hidden channel is reported rather than omitted`() = runBlocking<Unit> {
        val id = bound("隐藏台", "隐藏台.cn", EpgMatchType.MANUAL)
        database.channelDao().setHidden(id, hidden = true, updatedAt = now)
        repository.replaceAll("隐藏台.cn", listOf(row("隐藏台.cn", now, now + 30 * 60_000L)))

        val row = reader.bindingReport().rows.single { it.channelId == id }

        // The QA run's CCTV1 was hidden and therefore unobservable in the grid; the evidence entry
        // must still answer for it, and say why it is not on screen.
        assertThat(row.hidden).isTrue()
        assertThat(row.programmesInWindow).isEqualTo(1)
    }

    private suspend fun bound(name: String, epgChannelId: String, match: EpgMatchType): Long =
        database.channelDao().insert(fixture(name)).also { id ->
            database.channelDao().setEpgBindings(listOf(EpgBinding(id, epgChannelId, match.name)), now)
        }

    private fun fixture(name: String) = ChannelEntity(
        id = 0,
        name = name,
        nameKey = Keys.nameKey(name),
        tvgId = null,
        groupKey = "cctv",
        groupTitle = "央视",
        logo = null,
        channelNo = null,
        favorite = false,
        hidden = false,
        sortOrder = 0,
        epgChannelId = null,
        epgMatch = "NONE",
        createdAt = now,
        updatedAt = now,
    )

    private fun row(epgChannelId: String, startMs: Long, stopMs: Long): Programme = Programme(
        id = 0,
        epgChannelId = epgChannelId,
        startMs = startMs,
        stopMs = stopMs,
        title = "P",
        desc = null,
        category = null,
    )
}
