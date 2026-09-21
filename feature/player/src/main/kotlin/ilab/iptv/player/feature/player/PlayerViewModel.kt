package ilab.iptv.player.feature.player

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.player.PlaybackSession
import ilab.iptv.player.core.ui.player.PlayerContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The player screen's one-way data flow (docs/02 §8.1): it resolves the channel and its first
 * candidate stream through the [ChannelRepository] port, hands them to the single
 * [PlaybackSession], and forwards the session's [PlaybackUiState] to the view. The view never talks
 * to a repository or to an engine.
 *
 * The screen-level [fault] covers the two failures the session cannot see, because they happen
 * before it is asked to play anything: the channel does not exist, or it has no stream at all. Both
 * must land in the same readable overlay as a playback failure (P1-4 item 3).
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val channels: ChannelRepository,
    private val session: PlaybackSession,
) : ViewModel() {

    /** The session's state is the UI's state (docs/02 §4.5 C1) — no second copy lives here. */
    val playback: StateFlow<PlaybackUiState> = session.state

    private val _fault = MutableStateFlow<String?>(null)
    val fault: StateFlow<String?> = _fault.asStateFlow()

    private var request: PlayerContract.Input? = null

    /** Entry point from `PlayerContract` (docs/02 §8.1). */
    fun start(input: PlayerContract.Input) {
        request = input
        viewModelScope.launch {
            _fault.value = null
            val item = channels.get(input.channelId)
            if (item == null) {
                _fault.value = "找不到频道（编号 ${input.channelId}）"
                return@launch
            }
            val stream = selectStream(item.streams, input.streamId)
            if (stream == null) {
                _fault.value = "「${item.channel.name}」没有可用的流"
                return@launch
            }
            val result = session.watch(item.channel, stream)
            if (result is AppResult.Ok && !input.autoplay) session.pause()
        }
    }

    /** Failure-overlay button: re-run the whole path (channel lookup included) or just the stream. */
    fun retry() {
        val current = request ?: return
        if (_fault.value != null) {
            start(current)
            return
        }
        viewModelScope.launch {
            val result = session.retry()
            if (result == null) start(current)
        }
    }

    /** OK on the "画幅" control: next of the four §7.4 modes. */
    fun cycleAspectRatio(): AspectRatioMode {
        val next = AspectRatioCycle.next(playback.value.aspectRatio)
        session.setAspectRatio(next)
        return next
    }

    fun attachSurface(surface: Surface?) = session.attachSurface(surface)

    /** The screen is gone for good: end the session, keep the engine instance (§7.2 P3/P5). */
    fun onPlayerClosed() = session.stop(reason = "screen-closed")

    /** docs/02 §8.2: an explicit起始流 wins, otherwise the repository's first candidate. */
    private fun selectStream(streams: List<Stream>, streamId: Long?): Stream? =
        streamId?.let { id -> streams.firstOrNull { it.id == id } } ?: streams.firstOrNull()
}
