package ilab.iptv.player.feature.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.feature.epg.grid.TimeAxis
import ilab.iptv.player.feature.epg.grid.TimeWindow
import ilab.iptv.player.feature.epg.grid.WindowPlanner
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the grid draws (docs/02 §8.1: immutable state, `StateFlow`, view never touches a repository).
 *
 * `loadedChannelIds` is what tells the view whether a row with a binding has simply not been asked for
 * yet (`LOADING`) or has been asked for and has nothing (`NO_EPG`) — the two placeholders must not look
 * the same to the user.
 */
data class EpgGridUiState(
    val channels: List<Channel>,
    val window: TimeWindow,
    val nowMs: Long,
    val programmesByChannel: Map<Long, List<Programme>>,
    val loadedChannelIds: Set<Long>,
) {
    val loaded: Boolean get() = channels.isNotEmpty()
}

/**
 * The P3-1 view model: channels + the EPG window, both through domain ports.
 *
 * The query is built from three inputs — the visible time window, the visible channel page and the
 * channel table — and re-issued only when one of them changes. The page is page-aligned by
 * [WindowPlanner.channelPage], so a slow scroll inside a page produces no new query at all; that is the
 * §8.3 requirement to query a window instead of pulling the table.
 *
 * A failing query degrades to "loading" placeholders instead of an empty screen: the grid is a viewer of
 * the guide, and "the guide is broken" must not look like "there are no channels".
 */
@HiltViewModel
class EpgGridViewModel @Inject constructor(
    channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val clock: Clock,
) : ViewModel() {

    private val timeAxis = TimeAxis()
    private val window = MutableStateFlow(WindowPlanner.initialWindow(clock.nowMs(), timeAxis))
    private val page = MutableStateFlow<List<Long>>(emptyList())

    private val channels: StateFlow<List<Channel>> = channelRepository.observe(FILTER)
        .map { rows -> rows.map { it.channel } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** `now` moves the red line; a minute is enough resolution for a 6-hour window. */
    private val now: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMs())
            delay(NOW_TICK_MS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pageData: Flow<PageData> = combine(window, page, channels) { window, ids, channels ->
        PageRequest(window, ids, channels)
    }
        .distinctUntilChanged()
        .flatMapLatest { request -> load(request) }

    val uiState: StateFlow<EpgGridUiState> = combine(channels, window, pageData, now) { channels, window, data, nowMs ->
        EpgGridUiState(
            channels = channels,
            window = window,
            nowMs = nowMs,
            programmesByChannel = data.programmesByChannel,
            loadedChannelIds = data.loadedChannelIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EpgGridUiState(
            channels = emptyList(),
            window = window.value,
            nowMs = clock.nowMs(),
            programmesByChannel = emptyMap(),
            loadedChannelIds = emptySet(),
        ),
    )

    /** The view reports the rows it just built; the page only changes when a page boundary is crossed. */
    fun onVisibleRows(firstRow: Int, lastRow: Int) {
        val ids = WindowPlanner.channelPage(firstRow, lastRow, channels.value.map { it.id })
        if (ids != page.value) page.value = ids
    }

    /** The cursor is within an hour of a window edge: extend it so the step keys never hit a wall. */
    fun onCursorMoved(timeMs: Long) {
        val extended = WindowPlanner.ensureCursorVisible(timeMs, window.value)
        if (extended != window.value) window.value = extended
    }

    private fun load(request: PageRequest): Flow<PageData> {
        val wanted = request.channelIds.toSet()
        if (wanted.isEmpty()) return flowOf(PageData(emptyMap(), emptySet()))
        // `programme` is keyed on the EPG channel id; map it back to the business channel id here, the
        // layer that owns the identity hop (`RoomEpgRepository` does the same thing in SQL).
        val channelIdByEpgId = request.channels
            .filter { it.epgChannelId != null && it.id in wanted }
            .associate { it.epgChannelId!! to it.id }
        val query = EpgWindowQuery(
            fromMs = request.window.fromMs,
            toMs = request.window.toMs,
            channelIds = request.channelIds,
            limit = QUERY_CHANNEL_LIMIT,
        )
        return epgRepository.observeWindow(query)
            .map { programmes ->
                PageData(
                    programmesByChannel = programmes.groupBy { channelIdByEpgId[it.epgChannelId] }
                        .mapNotNull { (channelId, rows) -> channelId?.let { it to rows } }
                        .toMap(),
                    loadedChannelIds = wanted,
                )
            }
            .catch { emit(PageData(emptyMap(), wanted)) }
    }

    private data class PageRequest(
        val window: TimeWindow,
        val channelIds: List<Long>,
        val channels: List<Channel>,
    )

    private data class PageData(
        val programmesByChannel: Map<Long, List<Programme>>,
        val loadedChannelIds: Set<Long>,
    )

    private companion object {
        /** The grid shows the visible table; hidden channels stay out of it, exactly like the browse list. */
        val FILTER = ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null)

        /** docs/02 §8.3: a query is capped at 64 channels. Two 32-channel pages plus their prefetch. */
        const val QUERY_CHANNEL_LIMIT = 64

        const val NOW_TICK_MS = 30_000L
    }
}
