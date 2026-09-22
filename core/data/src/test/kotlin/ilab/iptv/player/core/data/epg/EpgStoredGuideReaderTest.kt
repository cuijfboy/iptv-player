package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.dao.EpgBinding
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.model.EpgGridWindow
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.core.source.normalize.Keys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trigger gate's second input (BUG-20260922-016): three counts that say whether the stored guide is
 * worth calling fresh. Two things matter beyond the counting itself — an empty table must be reported
 * as empty (that is what makes the gate wait for the catalogue instead of fetching into nothing), and
 * "programmed" must use the grid's window, not the retention window (BUG-018), or the gate would call a
 * blank TV fresh again.
 */
@RunWith(AndroidJUnit4::class)
class EpgStoredGuideReaderTest {

    private lateinit var database: IptvDatabase
    private lateinit var clock: Clock
    private lateinit var repository: RoomEpgRepository
    private lateinit var reader: RoomEpgStoredGuideReader

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = RoomFixtures.inMemoryDatabase()
        clock = RoomFixtures.clock(now)
        repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), clock)
        reader = RoomEpgStoredGuideReader(database.channelDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an unseeded channel table reads as an empty catalogue`() = runBlocking<Unit> {
        val guide = reader.read()

        assertThat(guide.channels).isEqualTo(0)
        assertThat(guide.catalogReady).isFalse()
        // The gate's rule: nothing to bind → wait, do not fetch (and do not stamp freshness).
        assertThat(guide.empty).isFalse()
    }

    @Test
    fun `only bindings that hold a programme inside the grid's window count as programmed`() =
        runBlocking<Unit> {
            val grid = EpgGridWindow.of(now)
            val onNow = bound("正在播")
            bound("明晚")
            database.channelDao().insert(unbound("没有绑定"))
            repository.replaceAll("正在播.cn", listOf(row("正在播.cn", now, now + 30 * 60_000L)))
            // 20 h out: inside the `[now-6h, now+48h]` retention window, outside the grid's six hours.
            // The pre-BUG-018 read would have counted this channel as covered.
            repository.replaceAll("明晚.cn", listOf(row("明晚.cn", now + 20 * 3_600_000L, now + 21 * 3_600_000L)))

            val guide = reader.read()

            assertThat(guide.channels).isEqualTo(3)
            assertThat(guide.matched).isEqualTo(2)
            assertThat(guide.programmed).isEqualTo(1)
            assertThat(guide.empty).isFalse()
            assertThat(grid.overlaps(now, now + 30 * 60_000L)).isTrue()
            assertThat(grid.overlaps(now + 20 * 3_600_000L, now + 21 * 3_600_000L)).isFalse()
        }

    @Test
    fun `bindings that all point at blank guides read as an empty guide`() = runBlocking<Unit> {
        bound("空白")
        database.channelDao().insert(unbound("没有绑定"))

        val guide = reader.read()

        assertThat(guide.matched).isEqualTo(1)
        assertThat(guide.programmed).isEqualTo(0)
        // Not "nothing to bind" but "bound and blank": the gate's `empty_guide` arm, retried after the
        // short backoff rather than after the full six hours.
        assertThat(guide.catalogReady).isTrue()
        assertThat(guide.empty).isTrue()
    }

    private suspend fun bound(name: String): Long = database.channelDao().insert(fixture(name)).also { id ->
        database.channelDao().setEpgBindings(listOf(EpgBinding(id, "$name.cn", "MANUAL")), now)
    }

    private fun unbound(name: String): ChannelEntity = fixture(name)

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
