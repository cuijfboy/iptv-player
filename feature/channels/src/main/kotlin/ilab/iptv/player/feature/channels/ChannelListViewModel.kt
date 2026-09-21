package ilab.iptv.player.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.model.ChannelFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    repository: ChannelRepository,
) : ViewModel() {

    private val filter = ChannelFilter(
        group = null,
        favoritesOnly = false,
        includeHidden = false,
        query = null,
    )

    val uiState: StateFlow<ChannelListUiState> = repository.observe(filter)
        .map { items ->
            val channels = items.map { it.channel }
            ChannelListUiState(
                rows = ChannelListRows.build(channels),
                channelCount = channels.size,
                streamCount = channels.sumOf { it.streamCount },
                groupCount = channels.map { it.groupKey }.distinct().size,
                loaded = true,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ChannelListUiState.Loading,
        )
}

/** What the browse screen renders (docs/02 §8.1: immutable `data class`, `StateFlow`-exposed). */
data class ChannelListUiState(
    val rows: List<ChannelListRow>,
    val channelCount: Int,
    val streamCount: Int,
    val groupCount: Int,
    val loaded: Boolean,
) {
    companion object {
        val Loading = ChannelListUiState(emptyList(), 0, 0, 0, loaded = false)
    }
}
