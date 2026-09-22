package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.Programme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The EPG port over a real (in-memory) Room database: the identity hop `channel.id → epg_channel_id →
 * programme`, now/next, the grid window, idempotent writes and §5.1's retention prune.
 */
@RunWith(AndroidJUnit4::class)
class RoomEpgRepositoryTest {

    private lateinit var database: IptvDatabase
    private lateinit var repository: RoomEpgRepository

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = RoomFixtures.inMemoryDatabase()
        repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), RoomFixtures.clock(now))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun channel(
        name: String,
        epgChannelId: String?,
        groupKey: String = "cctv",
        epgMatch: String = if (epgChannelId == null) "NONE" else "TVG_ID",
    ): Long = database.channelDao().insert(
        ChannelEntity(
            id = 0,
            name = name,
            nameKey = name.lowercase(),
            tvgId = null,
            groupKey = groupKey,
            groupTitle = groupKey,
            logo = null,
            channelNo = null,
            favorite = false,
            hidden = false,
            sortOrder = 0,
            epgChannelId = epgChannelId,
            epgMatch = epgMatch,
            createdAt = now,
            updatedAt = now,
        ),
    )

    private fun programme(
        epgChannelId: String,
        startMs: Long,
        stopMs: Long = startMs + 30 * 60 * 1000L,
        title: String = "P@$startMs",
    ) = Programme(0, epgChannelId, startMs, stopMs, title, null, null)

    @Test
    fun `now and next are resolved through the channel's epg binding`() = runBlocking<Unit> {
        val channelId = channel("CCTV-1", epgChannelId = "CCTV1.cn")
        repository.replaceAll(
            "CCTV1.cn",
            listOf(
                programme("CCTV1.cn", now - 30 * 60_000L, now + 30 * 60_000L, "on now"),
                programme("CCTV1.cn", now + 30 * 60_000L, now + 60 * 60_000L, "next"),
                programme("CCTV1.cn", now + 60 * 60_000L, now + 90 * 60_000L, "later"),
            ),
        )

        val nowNext = repository.nowNext(channelId, now)!!
        assertThat(nowNext.now?.title).isEqualTo("on now")
        assertThat(nowNext.next?.title).isEqualTo("next")
    }

    @Test
    fun `a channel with no epg binding answers with two nulls, not with an error`() = runBlocking<Unit> {
        val channelId = channel("本地台", epgChannelId = null)
        val nowNext = repository.nowNext(channelId, now)!!
        assertThat(nowNext.now).isNull()
        assertThat(nowNext.next).isNull()
    }

    @Test
    fun `an unknown channel is null, which is a different answer from no epg`() = runBlocking<Unit> {
        assertThat(repository.nowNext(channelId = 999, atMs = now)).isNull()
    }

    @Test
    fun `now is the slot covering the instant, not the most recent one that started`() = runBlocking<Unit> {
        val channelId = channel("CCTV-1", epgChannelId = "CCTV1.cn")
        repository.replaceAll(
            "CCTV1.cn",
            listOf(
                programme("CCTV1.cn", now - 120 * 60_000L, now - 60 * 60_000L, "finished"),
                programme("CCTV1.cn", now - 10 * 60_000L, now + 20 * 60_000L, "running"),
            ),
        )
        assertThat(repository.nowNext(channelId, now)!!.now?.title).isEqualTo("running")
    }

    @Test
    fun `the grid window returns the requested channels and respects the channel cap`() = runBlocking<Unit> {
        val first = channel("CCTV-1", epgChannelId = "c1")
        val second = channel("CCTV-2", epgChannelId = "c2")
        channel("CCTV-3", epgChannelId = "c3")
        repository.replaceAll("c1", listOf(programme("c1", now, title = "one")))
        repository.replaceAll("c2", listOf(programme("c2", now, title = "two")))
        repository.replaceAll("c3", listOf(programme("c3", now, title = "three")))

        val rows = repository.observeWindow(
            EpgWindowQuery(fromMs = now - 3_600_000L, toMs = now + 3_600_000L, channelIds = listOf(first, second), limit = 64),
        ).first()

        assertThat(rows.map { it.title }).containsExactly("one", "two")
    }

    @Test
    fun `writing the same slot twice updates it instead of duplicating it`() = runBlocking<Unit> {
        repository.replaceAll("c1", listOf(programme("c1", now, title = "old")))
        repository.replaceAll("c1", listOf(programme("c1", now, title = "new")))

        assertThat(database.programmeDao().countForChannel("c1")).isEqualTo(1)
        assertThat(database.programmeDao().now("c1", now + 1)!!.title).isEqualTo("new")
    }

    @Test
    fun `replaceAll ignores rows for another epg channel`() = runBlocking<Unit> {
        val written = repository.replaceAll(
            "c1",
            listOf(programme("c1", now), programme("c2", now)),
        )
        assertThat(written).isEqualTo(1)
        assertThat(database.programmeDao().countForChannel("c2")).isEqualTo(0)
    }

    @Test
    fun `prune drops what ended before the window and what starts after it`() = runBlocking<Unit> {
        val from = now - 6 * 3_600_000L
        val to = now + 48 * 3_600_000L
        repository.replaceAll(
            "c1",
            listOf(
                programme("c1", from - 3_600_000L, from - 1_800_000L, "too old"),
                programme("c1", now, title = "kept"),
                programme("c1", to + 3_600_000L, to + 7_200_000L, "too new"),
            ),
        )

        val deleted = repository.prune(from, to)

        assertThat(deleted).isEqualTo(2)
        assertThat(database.programmeDao().countForChannel("c1")).isEqualTo(1)
    }

    @Test
    fun `coverage counts matched channels and breaks them down by group`() = runBlocking<Unit> {
        channel("CCTV-1", epgChannelId = "c1", groupKey = "央视")
        channel("CCTV-2", epgChannelId = null, groupKey = "央视")
        channel("湖南卫视", epgChannelId = "hunan", groupKey = "卫视")

        val coverage = repository.coverage()

        assertThat(coverage.matched).isEqualTo(2)
        assertThat(coverage.total).isEqualTo(3)
        assertThat(coverage.byGroup).containsEntry(ChannelGroup.CCTV, 1)
        assertThat(coverage.byGroup).containsEntry(ChannelGroup.SATELLITE, 1)
        assertThat(coverage.byGroupTotal).containsEntry(ChannelGroup.CCTV, 2)
        assertThat(coverage.byGroupTotal).containsEntry(ChannelGroup.SATELLITE, 1)
    }

    @Test
    fun `coverage tells a served binding apart from one the guide declared and left empty`() =
        runBlocking<Unit> {
            // The panel's own read path (EPG-BIND): `c2` has an id and no programme, which is exactly
            // the "covered on paper, blank in the app" state the id-side count called a success.
            channel("CCTV-1", epgChannelId = "c1", groupKey = "央视")
            channel("CCTV-2", epgChannelId = "c2", groupKey = "央视")
            channel("湖南卫视", epgChannelId = null, groupKey = "卫视")
            repository.replaceAll("c1", listOf(programme("c1", now, now + 1_800_000L)))

            val coverage = repository.coverage()

            assertThat(coverage.matched).isEqualTo(2)
            assertThat(coverage.withProgrammes).isEqualTo(1)
            assertThat(coverage.emptyBinding).isEqualTo(1)
            assertThat(coverage.byGroupWithProgrammes).containsExactly(ChannelGroup.CCTV, 1)
            assertThat(coverage.programmedRatio).isEqualTo(1.0 / 3.0)
        }

    @Test
    fun `a row that has aged out of the window is not coverage any more`() = runBlocking<Unit> {
        channel("CCTV-1", epgChannelId = "c1", groupKey = "央视")
        // Still in the table (nothing has pruned it yet), but outside `[now-6h, now+48h]`: the window
        // is a property of time, so the reading must not count it.
        repository.replaceAll(
            "c1",
            listOf(programme("c1", now - 8 * 3_600_000L, now - 7 * 3_600_000L, "老节目")),
        )

        val coverage = repository.coverage()

        assertThat(coverage.matched).isEqualTo(1)
        assertThat(coverage.withProgrammes).isEqualTo(0)
        assertThat(coverage.emptyBinding).isEqualTo(1)
        // The group is reported as zero rather than left out, so the panel's slice cannot read the
        // omission as "not reported" and fall back to the id-side number.
        assertThat(coverage.byGroupWithProgrammes).containsExactly(ChannelGroup.CCTV, 0)
    }
}
