package ilab.iptv.player.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.model.ChannelFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * docs/02 §8.1's one-way data flow: the view renders [uiState] and never touches a repository.
 *
 * The view model knows nothing about Room, the parser or the fixture — it asks the
 * [ChannelRepository] port for channels, flattens them into rows and counts them for the header.
 * Row building runs on [Dispatchers.Default] because it is O(n log n) over 658 channels and the
 * browse screen must not do that on the main thread (docs/02 §8.4 budget).
 */
@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {

    /**
     * The two list-wide switches P2-2 adds, kept in the view model (not the repository) because they
     * are view state: "only favourites" and "show the hidden ones too". The repository's
     * [ChannelFilter] is the frozen port that already carries them, so no new port was needed.
     */
    data class Filters(val favoritesOnly: Boolean = false, val includeHidden: Boolean = false)

    private val filters = MutableStateFlow(Filters())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val items = filters.flatMapLatest { current ->
        repository.observe(
            ChannelFilter(
                group = null,
                favoritesOnly = current.favoritesOnly,
                includeHidden = current.includeHidden,
                query = null,
            ),
        )
    }

    val uiState: StateFlow<ChannelListUiState> = combine(items, filters) { loaded, current ->
        ChannelListUiState(
            rows = ChannelListRows.build(loaded.map { it.channel }),
            channelCount = loaded.size,
            streamCount = loaded.sumOf { it.channel.streamCount },
            groupCount = loaded.map { it.channel.groupKey }.distinct().size,
            loaded = true,
            favoritesOnly = current.favoritesOnly,
            includeHidden = current.includeHidden,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ChannelListUiState.Loading,
        )

    fun toggleFavoritesOnly() {
        filters.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun toggleIncludeHidden() {
        filters.update { it.copy(includeHidden = !it.includeHidden) }
    }

    /** docs/01 F5: favourite / hidden / number / order are the four user-owned channel columns. */
    fun toggleFavorite(item: ChannelListRow.ChannelItem) {
        viewModelScope.launch { repository.setFavorite(item.channelId, !item.favorite) }
    }

    fun setHidden(item: ChannelListRow.ChannelItem, hidden: Boolean) {
        viewModelScope.launch { repository.setHidden(item.channelId, hidden) }
    }

    /**
     * docs/01 D12: a typed number, or null to clear the user's edit. The repository persists it into
     * `channel.channel_no`; the list then shows it as the channel's number.
     */
    fun setChannelNo(item: ChannelListRow.ChannelItem, number: Int?) {
        viewModelScope.launch { repository.setChannelNo(item.channelId, number) }
    }

    /**
     * Moves [item] by [delta] places inside its own `group_key` (docs/02 §5.1 / §8.1: a move never
     * crosses a section). The index is the channel's position among the *rendered* rows of its group,
     * which is exactly what the repository's `reorder` rewrites `sort_order` for.
     */
    fun move(item: ChannelListRow.ChannelItem, delta: Int) {
        val rendered = uiState.value.rows.filterIsInstance<ChannelListRow.ChannelItem>()
        val section = rendered.filter { it.groupKey == item.groupKey }
        val from = section.indexOfFirst { it.channelId == item.channelId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, section.size - 1)
        if (to == from) return
        viewModelScope.launch { repository.reorder(item.channelId, to) }
    }
}

/** What the browse screen renders (docs/02 §8.1: immutable `data class`, `StateFlow`-exposed). */
data class ChannelListUiState(
    val rows: List<ChannelListRow>,
    val channelCount: Int,
    val streamCount: Int,
    val groupCount: Int,
    val loaded: Boolean,
    /** Echoed back so the two header toggles render their pressed state (P2-2). */
    val favoritesOnly: Boolean = false,
    val includeHidden: Boolean = false,
) {
    companion object {
        val Loading = ChannelListUiState(emptyList(), 0, 0, 0, loaded = false)
    }
}
