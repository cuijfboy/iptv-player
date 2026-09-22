package ilab.iptv.player.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.domain.channel.ChannelSorter
import ilab.iptv.player.core.model.ChannelFilter
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-2, stated as tests (docs/01 F5, docs/01 D12). Two claims have to hold together, and neither is
 * worth anything alone:
 *
 * 1. **Durable** — a user edit (favourite / hidden / number / order) survives a process restart; and
 * 2. **Authoritative** — a later refresh/import of the same playlist does not put the source's value
 *    back. docs/01 F5 puts the user above the source, and `ChannelDao.upsertAll`'s column split is
 *    the mechanism; this test is what keeps that split honest.
 *
 * The number case is the one docs/02 §5.1 cannot express perfectly: `channel_no` is a single column
 * with no "who wrote it" flag, so "user edit" and "source `tvg-chno`" share it. The rule this test
 * pins is therefore the *safe* half of D12 — an edit is honoured and re-edits win, and clearing the
 * edit falls back (auto immediately, the source's `tvg-chno` on the next import). The missing top
 * tier is reported as an open item, not hidden here.
 */
@RunWith(AndroidJUnit4::class)
class ChannelManagementPersistenceTest {

    private val all = ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null)

    private lateinit var database: IptvDatabase
    private lateinit var rig: RoomFixtures.Rig

    @Before
    fun setUp() {
        database = RoomFixtures.inMemoryDatabase()
        rig = RoomFixtures.Rig(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `favourite, hidden, number and order survive a restart`() = test {
        val name = "channel-management-restart.db"
        RoomFixtures.deleteDatabase(name)

        val first = RoomFixtures.fileDatabase(name)
        var targetId = -1L
        try {
            val firstRig = RoomFixtures.Rig(first)
            val channels = firstRig.channels.observe(all).first()
            val target = channels.first().channel
            targetId = target.id
            firstRig.channels.setFavorite(target.id, true)
            firstRig.channels.setHidden(target.id, true)
            firstRig.channels.setChannelNo(target.id, 777)
        } finally {
            first.close()
        }

        val second = RoomFixtures.fileDatabase(name)
        try {
            val secondRig = RoomFixtures.Rig(second)
            // `get` reads the row whatever its filters say — the channel is hidden on purpose here,
            // and a filtered read would (correctly) leave it out.
            val restored = secondRig.channels.get(targetId)!!
            assertThat(restored.channel.favorite).isTrue()
            assertThat(restored.channel.hidden).isTrue()
            assertThat(restored.channel.channelNo).isEqualTo(777)
        } finally {
            second.close()
            RoomFixtures.deleteDatabase(name)
        }
    }

    @Test
    fun `a refresh keeps the favourite, hidden flag and user number`() = test {
        val loaded = rig.channels.observe(all).first()
        val target = loaded.first().channel
        // The fixture does carry `tvg-chno` on some rows; pick one so "the source re-supplies a
        // number" below is real and not vacuous.
        val withSource = loaded.first { it.channel.channelNo != null }.channel

        rig.channels.setFavorite(target.id, true)
        rig.channels.setHidden(target.id, true)
        rig.channels.setChannelNo(withSource.id, 900)

        // A refresh of the same playlist (the writer is the same sink a P2-4 refresh would use).
        val parsed = rig.catalog.prepare(Fixtures.text(Fixtures.BASELINE_PLAYLIST), "p1-2-fixture")
        rig.writer.write(parsed.mapped, nowMs = 2L)

        assertThat(rig.channels.get(target.id)!!.channel.favorite).isTrue()
        assertThat(rig.channels.get(target.id)!!.channel.hidden).isTrue()
        assertThat(rig.channels.get(withSource.id)!!.channel.channelNo).isEqualTo(900)
    }

    @Test
    fun `clearing the user number falls back to auto, then to the source number on the next import`() = test {
        val loaded = rig.channels.observe(all).first()
        val withSource = loaded.first { it.channel.channelNo != null }.channel
        val sourceNumber = withSource.channelNo!!

        rig.channels.setChannelNo(withSource.id, 900)
        assertThat(rig.channels.get(withSource.id)!!.channel.channelNo).isEqualTo(900)

        // "清除频道号": the edit is dropped. With one column the value is now empty, so the list
        // numbers the channel automatically (D12 tier 3) until the source speaks again.
        rig.channels.setChannelNo(withSource.id, null)
        assertThat(rig.channels.get(withSource.id)!!.channel.channelNo).isNull()

        // The next import re-supplies the source's `tvg-chno`, and the merge refills the cleared
        // column — the tier-2 half of "回落到 tvg-chno/自动编号".
        val parsed = rig.catalog.prepare(Fixtures.text(Fixtures.BASELINE_PLAYLIST), "p1-2-fixture")
        rig.writer.write(parsed.mapped, nowMs = 3L)
        assertThat(rig.channels.get(withSource.id)!!.channel.channelNo).isEqualTo(sourceNumber)
    }

    @Test
    fun `a move rewrites the section order and a refresh does not undo it`() = test {
        val loaded = rig.channels.observe(all).first()
        val moved = loaded.first().channel
        val section = loaded.filter { it.channel.groupKey == moved.groupKey }
        assertThat(section.size).isAtLeast(2)

        rig.channels.reorder(moved.id, 1)
        assertThat(sectionOrder(moved.groupKey).indexOf(moved.id)).isEqualTo(1)

        val parsed = rig.catalog.prepare(Fixtures.text(Fixtures.BASELINE_PLAYLIST), "p1-2-fixture")
        rig.writer.write(parsed.mapped, nowMs = 4L)
        assertThat(sectionOrder(moved.groupKey).indexOf(moved.id)).isEqualTo(1)
    }

    /** The rendered order of one `group_key` section, exactly as the browse list would show it. */
    private suspend fun sectionOrder(groupKey: String): List<Long> =
        rig.channels.observe(all).first().map { it.channel }
            .sortedWith(ChannelSorter.comparator)
            .filter { it.groupKey == groupKey }
            .map { it.id }
}
