package ilab.iptv.player.feature.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.ChannelWithStreams
import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.NowNext
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.feature.epg.grid.EpgRowState
import ilab.iptv.player.feature.epg.grid.GridFrameBuilder
import ilab.iptv.player.feature.epg.grid.GridFrameInput
import ilab.iptv.player.feature.epg.grid.GridMetrics
import ilab.iptv.player.feature.epg.grid.ScrollOffset
import ilab.iptv.player.feature.epg.grid.TimeAxis
import ilab.iptv.player.feature.epg.grid.TimeWindow
import ilab.iptv.player.feature.epg.grid.programmesToRowInput
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * BUG-20260922-018, end to end at the grid's own boundary: **every channel the diagnostics panel
 * counts must render at least one block, in the same window.**
 *
 * The panel (`RoomEpgBindingReader`) asks per channel; the grid asks per page (`EpgGridViewModel` →
 * `EpgRepository.observeWindow` → the identity hop home). This test drives the view model against a
 * repository double that speaks the **production** contract — programmes are keyed on the guide id,
 * only the asked-for guide ids come back, and the window predicate is the DAO's
 * (`stop_ms >= from AND start_ms <= to`) — then feeds the resulting state through the real
 * [GridFrameBuilder], the way `EpgGridView` does.
 *
 * The regression it pins: `CCTV1` and `CCTV1 高清` are two rows bound to **one** guide id (`CCTV-1.hk`).
 * A one-to-one hop (`associate`) kept only the later row, so the earlier one rendered as "no EPG" while
 * the panel — and this test — counted `CCTV-1.hk`'s programmes for it. Both rows must show the guide.
 *
 * Pure JVM: no Robolectric, no device, no Room (the Room side of the same property is asserted in
 * `:core:data`'s `RoomEpgBindingReaderTest` and `RoomEpgRepositoryTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpgGridViewModelTest {

    /** A Wednesday-ish instant, matched to the zone the grid lays its ruler out in. */
    private val now = 1_790_000_000_000L
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val minute = 60_000L

    private val sd = channel(1, "CCTV1", "CCTV-1.hk", no = 1)
    private val hd = channel(2, "CCTV1 高清", "CCTV-1.hk", no = 2)
    private val cctv2 = channel(3, "CCTV2", "CCTV-2.hk", no = 3)
    private val cctv3 = channel(4, "CCTV3", "CCTV-3.hk", no = 4)
    private val unbound = channel(5, "没有绑定的台", null, no = 5)
    private val blankGuide = channel(6, "绑了空指南", "empty.hk", no = 6)
    private val channels = listOf(sd, hd, cctv2, cctv3, unbound, blankGuide)

    /**
     * The guides this fixture has, in the window the grid opens on: dense enough to cover the visible
     * time band (a real CCTV guide is), one channel with nothing, and a row whose programmes are
     * entirely *outside* the window so the DAO predicate has something to reject.
     */
    private val guide: Map<String, List<Programme>> = buildMap {
        val window = ilab.iptv.player.core.model.EpgGridWindow.of(now, zone)
        var id = 100L
        fun fill(epgId: String, slots: Int) {
            val programmes = ArrayList<Programme>(slots)
            for (slot in 0 until slots) {
                val start = window.fromMs + slot * 60 * minute
                programmes += Programme(
                    id = id++,
                    epgChannelId = epgId,
                    startMs = start,
                    stopMs = start + 60 * minute,
                    title = "$epgId #$slot",
                    desc = null,
                    category = null,
                )
            }
            put(epgId, programmes)
        }
        fill("CCTV-1.hk", 6)
        fill("CCTV-2.hk", 6)
        // Only one slot inside the window: still a block on screen (the "thin but real" case).
        put(
            "CCTV-3.hk",
            listOf(
                Programme(id = id++, epgChannelId = "CCTV-3.hk", startMs = now, stopMs = now + 30 * minute, title = "窄档", desc = null, category = null),
            ),
        )
        // A guide id that exists but is empty inside the window: bound, loaded, honestly blank.
        put("empty.hk", listOf())
        // Outside the window entirely: the DAO would not return it and neither does the double.
        put(
            "outside.hk",
            listOf(
                Programme(
                    id = id++,
                    epgChannelId = "outside.hk",
                    startMs = window.toMs + 5 * 60 * minute,
                    stopMs = window.toMs + 35 * 60 * minute,
                    title = "窗外",
                    desc = null,
                    category = null,
                ),
            ),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The card's assertion, per channel: a channel whose guide id holds ≥1 programme inside the window
     * (what the panel prints as `窗口内 N 条`) renders ≥1 block; a channel that is bound with nothing in
     * the window renders none but is still **loaded** (never confused with "not asked yet").
     */
    @Test
    fun `every channel the panel counts renders at least one block`() = runBlocking<Unit> {
        val state = gridStateAfterFirstPage()

        val rows = state.channels.map { channel ->
            val programmes = if (channel.id in state.loadedChannelIds) {
                state.programmesByChannel[channel.id] ?: emptyList()
            } else {
                null
            }
            programmesToRowInput(
                channelId = channel.id,
                name = channel.name,
                channelNo = channel.channelNo,
                programmes = programmes,
                hasBinding = channel.epgChannelId != null,
            )
        }
        assertThat(state.loadedChannelIds).containsExactlyElementsIn(channels.map { it.id })

        val frame = GridFrameBuilder(TimeAxis(zone)).build(
            GridFrameInput(
                metrics = GridMetrics.fromDensity(2f),
                viewportWidthPx = 1920,
                viewportHeightPx = 1080,
                contentWindow = TimeWindow(state.window.fromMs, state.window.toMs),
                scroll = ScrollOffset.ZERO,
                rows = rows,
                nowMs = state.nowMs,
            ),
        )
        val blocksByChannel = frame.rows.associate { it.channelId to it.blocks.size }
        val stateByChannel = frame.rows.associate { it.channelId to it.state }
        assertThat(blocksByChannel.keys).containsExactlyElementsIn(channels.map { it.id }).inOrder()

        for (channel in channels) {
            val countedByPanel = windowCount(channel.epgChannelId)
            if (countedByPanel > 0) {
                assertThat(blocksByChannel[channel.id]).isAtLeast(1)
            }
        }
        // The two rows that share `CCTV-1.hk` both render the guide — the BUG-018 regression itself.
        assertThat(blocksByChannel[sd.id]).isAtLeast(1)
        assertThat(blocksByChannel[hd.id]).isAtLeast(1)
        // Bound and empty stays distinguishable from "loading": loaded state, no block.
        assertThat(stateByChannel[blankGuide.id]).isEqualTo(EpgRowState.LOADED)
        assertThat(blocksByChannel[blankGuide.id]).isEqualTo(0)
        // No binding stays NO_EPG (a different placeholder, no query behind it).
        assertThat(stateByChannel[unbound.id]).isEqualTo(EpgRowState.NO_EPG)
    }

    /** The state the view model hands the view for the first page, and the shared-guide invariant. */
    @Test
    fun `channels sharing one guide id are both loaded with that guide`() = runBlocking<Unit> {
        val state = gridStateAfterFirstPage()

        assertThat(state.programmesByChannel[sd.id]).isNotEmpty()
        assertThat(state.programmesByChannel[sd.id]).isEqualTo(state.programmesByChannel[hd.id])
        assertThat(state.programmesByChannel[hd.id]).isNotEmpty()
        // A channel with no binding gets nothing (the row is NO_EPG, not an empty guide).
        assertThat(state.programmesByChannel).doesNotContainKey(unbound.id)
        // A channel bound to a guide that is empty in the window gets an empty list, not a missing key.
        assertThat(state.programmesByChannel).doesNotContainKey(blankGuide.id)
    }

    private suspend fun gridStateAfterFirstPage(): EpgGridUiState {
        val viewModel = EpgGridViewModel(
            channelRepository = FakeChannelRepository(channels),
            epgRepository = FakeEpgRepository(channels, guide),
            clock = FixedClock(now),
        )
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.channels.isNotEmpty() } }
        viewModel.onVisibleRows(0, channels.size - 1)
        return withTimeout(TIMEOUT_MS) { viewModel.uiState.first { it.loadedChannelIds.isNotEmpty() } }
    }

    /** How many programmes the panel's window query would count for a guide id (`窗口内 N 条`). */
    private fun windowCount(epgChannelId: String?): Int {
        if (epgChannelId.isNullOrBlank()) return 0
        val window = ilab.iptv.player.core.model.EpgGridWindow.of(now, zone)
        return guide[epgChannelId].orEmpty()
            .count { it.stopMs >= window.fromMs && it.startMs <= window.toMs }
    }

    private fun channel(id: Long, name: String, epgChannelId: String?, no: Int): Channel = Channel(
        id = id,
        name = name,
        nameKey = name.lowercase(),
        tvgId = null,
        group = ChannelGroup.CCTV,
        groupKey = "央视",
        logoUrl = null,
        channelNo = no,
        favorite = false,
        hidden = false,
        sortOrder = no,
        epgChannelId = epgChannelId,
        epgMatch = if (epgChannelId == null) EpgMatchType.NONE else EpgMatchType.NAME_EXACT,
        streamCount = 1,
    )

    private class FixedClock(private val at: Long) : Clock {
        override fun nowMs(): Long = at
    }

    /** The browse/grid list port: the fixture rows, in the order the grid rows are laid out. */
    private class FakeChannelRepository(private val rows: List<Channel>) : ChannelRepository {
        private val state = MutableStateFlow(rows.map { ChannelWithStreams(it, emptyList<Stream>()) })

        override fun observe(filter: ChannelFilter): Flow<List<ChannelWithStreams>> =
            if (filter.includeHidden) state else flowOf(state.value.filter { !it.channel.hidden })

        override suspend fun get(channelId: Long): ChannelWithStreams? =
            state.value.firstOrNull { it.channel.id == channelId }

        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit
        override suspend fun setHidden(channelId: Long, hidden: Boolean) = Unit
        override suspend fun reorder(channelId: Long, newIndex: Int) = Unit
        override suspend fun setChannelNo(channelId: Long, channelNo: Int?) = Unit
        override suspend fun setEpgBinding(channelId: Long, epgChannelId: String?, match: EpgMatchType) = Unit
        override suspend fun rename(channelId: Long, displayName: String?) = Unit
        override suspend fun setUserGroup(channelId: Long, groupTitle: String?) = Unit
        override suspend fun deleteChannels(channelIds: List<Long>): Int = 0
        override suspend fun restoreChannels(items: List<ChannelWithStreams>): Int = 0
        override suspend fun countByGroup(): Map<ChannelGroup, Int> =
            state.value.groupingBy { it.channel.group }.eachCount()
    }

    /**
     * The EPG port as `RoomEpgRepository` implements it: the page's business ids become guide ids, one
     * query answers them all, and the window predicate is the DAO's. The hop home is the view model's
     * job — the thing under test — so this double deliberately returns the flat, guide-keyed list.
     */
    private class FakeEpgRepository(
        private val channels: List<Channel>,
        private val guide: Map<String, List<Programme>>,
    ) : EpgRepository {

        override fun observeWindow(query: EpgWindowQuery): Flow<List<Programme>> {
            val wanted = query.channelIds.take(query.limit.coerceAtLeast(1)).toSet()
            val epgIds = channels
                .filter { it.id in wanted }
                .mapNotNull { it.epgChannelId?.takeIf { id -> id.isNotBlank() } }
                .distinct()
            val rows = epgIds.flatMap { epgId ->
                guide[epgId].orEmpty().filter { it.stopMs >= query.fromMs && it.startMs <= query.toMs }
            }
            return flowOf(rows)
        }

        override suspend fun nowNext(channelId: Long, atMs: Long): NowNext? = NowNext(null, null)

        override suspend fun coverage(): EpgCoverage =
            EpgCoverage(matched = 0, total = 0, byGroup = emptyMap())

        override suspend fun replaceAll(epgChannelId: String, programmes: List<Programme>): Int = 0

        override suspend fun prune(keepFromMs: Long, keepToMs: Long): Int = 0
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
