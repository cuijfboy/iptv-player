package ilab.iptv.player.feature.channels.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.domain.channel.ChannelNumberAssigner
import ilab.iptv.player.core.domain.channel.ChannelSorter
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.EpgWindowQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P3-2: channel-name + programme-name search over the data the app already has.
 *
 * INPUT SOURCES, both existing ports (no new one):
 *  - channels via `ChannelRepository.observe(...)` — the same list the browse screen shows, so a
 *    hidden channel is not searchable and the numbers are the ones the list displays;
 *  - the guide via `EpgRepository.observeWindow(...)`, paged 64 channels at a time because docs/02
 *    §8.3 caps one window query at 64 channels (that cap is the reason the index is built in pages
 *    rather than one call).
 *
 * TIMING: the index is built once, off the main thread, when the screen opens; every keystroke is
 * then a pure `SearchIndex.search` on `Dispatchers.Default`. The screen states which of the two is
 * happening, so "no results yet" is never mistaken for "established index, nothing matched".
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var index: SearchIndex? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { buildIndex() }
    }

    /**
     * The typed query. The answer lands asynchronously (a JVM search over ~40k keys), and the result
     * is applied only when it still belongs to the newest query — pressing a key twice must never put
     * the first query's hits back on screen.
     */
    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        val current = index ?: return
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val hits = current.search(text, clock.nowMs())
            _state.update { if (it.query == text) it.copy(hits = hits) else it }
        }
    }

    private suspend fun buildIndex() {
        val loaded = runCatching {
            channels.observe(
                ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null),
            ).first()
        }.getOrElse { error ->
            _state.update { it.copy(indexError = error.message ?: error.javaClass.simpleName) }
            return
        }

        // The browse list's own ordering/numbering (docs/01 D12) — "12 CCTV1" here must be the channel
        // the user sees at number 12 there.
        val numbered = ChannelNumberAssigner.assign(ChannelSorter.sort(loaded.map { it.channel }))
        val searchChannels = numbered.map {
            SearchChannel(
                channelId = it.channel.id,
                name = it.channel.name,
                number = it.number,
                groupKey = it.channel.groupKey,
            )
        }
        val nameById = loaded.associate { it.channel.id to it.channel.name }
        // programme rows carry the EPG channel id; the index is keyed on the business channel id.
        val channelIdByEpgId = buildMap {
            loaded.forEach { item ->
                item.channel.epgChannelId?.takeIf { it.isNotBlank() }?.let { put(it, item.channel.id) }
            }
        }

        val now = clock.nowMs()
        val programmes = mutableListOf<SearchProgramme>()
        numbered.map { it.channel.id }.chunked(EPG_PAGE_SIZE).forEach { page ->
            val rows = runCatching {
                epg.observeWindow(
                    EpgWindowQuery(
                        fromMs = now - EPG_PAST_MS,
                        toMs = now + EPG_FUTURE_MS,
                        channelIds = page,
                        limit = EPG_PAGE_SIZE,
                    ),
                ).first()
            }.getOrDefault(emptyList())
            rows.forEach { programme ->
                val channelId = channelIdByEpgId[programme.epgChannelId] ?: return@forEach
                programmes += SearchProgramme(
                    channelId = channelId,
                    channelName = nameById[channelId].orEmpty(),
                    title = programme.title,
                    startMs = programme.startMs,
                    stopMs = programme.stopMs,
                )
            }
        }

        val built = SearchIndex.build(searchChannels, programmes)
        index = built
        _state.update {
            it.copy(
                indexReady = true,
                channelCount = built.channelCount,
                programmeCount = built.programmeCount,
            )
        }
        // A query typed while the index was still building gets its answer now.
        setQuery(_state.value.query)
    }

    private companion object {
        /** docs/02 §8.3: one window query covers at most 64 channels. */
        const val EPG_PAGE_SIZE = 64

        /**
         * The indexed part of the guide: an hour back (a programme that started at 19:30 is still
         * "on now" at 20:05) and 24 h forward — the retention window §5.1 keeps anyway.
         */
        const val EPG_PAST_MS = 60L * 60L * 1000L
        const val EPG_FUTURE_MS = 24L * 60L * 60L * 1000L
    }
}

/** What the search screen renders (docs/02 §8.1: immutable state + `StateFlow`). */
data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val indexReady: Boolean = false,
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
    val indexError: String? = null,
)
