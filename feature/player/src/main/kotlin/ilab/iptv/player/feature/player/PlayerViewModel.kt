package ilab.iptv.player.feature.player

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.channel.NumberedChannel
import ilab.iptv.player.core.domain.playback.DefaultFailoverPolicy
import ilab.iptv.player.core.domain.playback.FailoverLimits
import ilab.iptv.player.core.domain.playback.PlaybackWatchdog
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.player.OverscanPolicy
import ilab.iptv.player.core.player.OverscanSettings
import ilab.iptv.player.core.player.PlaybackSession
import ilab.iptv.player.core.ui.player.PlayerContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The player screen's one-way data flow (docs/02 §8.1): it resolves the channel and its first
 * candidate stream through the [ChannelRepository] port, hands them to the single [PlaybackSession]
 * — through [PlaybackFailoverCoordinator], which owns the fail-over wiring of P1-5 — and forwards
 * the session's [PlaybackUiState] to the view. The view never talks to a repository or to an engine.
 *
 * THREE JOBS, in order:
 * 1. **resolve** the play input (`PlayerContract`) into a channel + stream, or the screen-level
 *    [fault] when the channel does not exist / has no stream at all — the two failures that happen
 *    before the session is asked to play anything (P1-4 item 3);
 * 2. **drive the fail-over wiring**: start the coordinator's session and let it retry/switch/give up
 *    (docs/02 §4.6) while the state it produces reaches the same info bar and overlay;
 * 3. **switch channels** on the remote (P1-5 item 1): UP/DOWN inside the current group and a direct
 *    jump by channel number, both resolved through [ChannelSwitchPlanner] so the order is the same one
 *    the browse list shows (docs/01 D12).
 *
 * Channel switches go through the coordinator's `open`, i.e. the SAME [PlaybackSession] and therefore
 * the same engine instance — §6.2's "换台时复用同一个 `PlayerEngine` 实例（`stop() + prepare()`）".
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val channels: ChannelRepository,
    private val session: PlaybackSession,
    private val failoverPolicy: DefaultFailoverPolicy,
    private val failoverCatalog: FailoverCatalog,
    private val watchdog: PlaybackWatchdog,
    private val failoverLimits: FailoverLimits,
    private val clock: Clock,
    private val logger: Logger,
    private val network: NetworkAvailability,
    /**
     * P2-7 item 4: the info bar's now/next. It is the *domain port*, not `:core:epg` — the guard's
     * rule 2 keeps a feature away from `:core:database` and `:core:epg`, so the only EPG a screen can
     * see is this interface (docs/02 §3.2).
     */
    private val epg: EpgRepository,
    /**
     * P3-3 item 3: the persisted overscan step. Injected as the `:core:player` interface so this
     * screen never sees `SharedPreferences`, and so the persistence claim is testable with a fake.
     */
    private val overscan: OverscanSettings,
) : ViewModel() {

    /** The session's state is the UI's state (docs/02 §4.5 C1) — no second copy lives here. */
    val playback: StateFlow<PlaybackUiState> = session.state

    private val _fault = MutableStateFlow<String?>(null)
    val fault: StateFlow<String?> = _fault.asStateFlow()

    /** P3-3 item 3: the current overscan ladder index, mirrored for the screen to render. */
    private val _overscanIndex = MutableStateFlow(OverscanPolicy.clamp(overscan.levelIndex))
    val overscanIndex: StateFlow<Int> = _overscanIndex.asStateFlow()

    /** Sort+number order of the whole catalog — the same order the browse list shows (§8.1/D12). */
    private val _order = MutableStateFlow<List<NumberedChannel>>(emptyList())
    val order: StateFlow<List<NumberedChannel>> = _order.asStateFlow()

    private val coordinator = PlaybackFailoverCoordinator(
        port = SessionPlaybackPort(session),
        policy = failoverPolicy,
        catalog = failoverCatalog,
        watchdog = watchdog,
        clock = clock,
        logger = logger,
        scope = viewModelScope,
        limits = failoverLimits,
    )

    private var request: PlayerContract.Input? = null

    /** The in-flight EPG lookup; a channel switch cancels it so no stale programme line can land. */
    private var nowNextJob: Job? = null

    /** P1-7 item 5: one retry per observed outage, and only while the screen shows a failure. */
    private val networkRetry = NetworkRetryWire(
        availability = network,
        inFailureState = ::inFailureState,
        retry = ::retry,
    )

    init {
        viewModelScope.launch {
            channels.observe(ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null))
                .collect { items -> _order.value = ChannelSwitchPlanner.numbered(items.map { it.channel }) }
        }
        // P1-7 item 5: when the network comes back and the channel is sitting in a failure state,
        // re-run the SAME retry path the failure overlay's button uses — no second retry mechanism.
        networkRetry.attach()
    }

    /** The two failure states the screen can show: a screen-level fault, or the session's ERROR phase. */
    private fun inFailureState(): Boolean =
        _fault.value != null || playback.value.phase == PlaybackPhase.ERROR

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
            // Started next to the playback request, not after it: the lookup is a database read and the
            // info bar should gain its programme line as soon as that answers, while `open` is still
            // waiting for the first frame.
            loadNowNext(item.channel.id)
            coordinator.open(item.channel, stream)
            if (!input.autoplay) {
                coordinator.setPaused(true)
                session.pause()
            }
        }
    }

    /**
     * Failure-overlay button: re-run the whole path. With fail-over wired, a manual retry starts a
     * fresh session on the same channel instead of one more attempt on the same stream — the policy's
     * switch budget and its permanent demotions are per session (docs/02 §4.3), and pressing "重试"
     * means "start this channel again", not "reuse a source that was just given up on".
     */
    fun retry() {
        val current = request ?: return
        start(current)
    }

    // ---------------------------------------------------------------- channel switching (P1-5 item 1)

    /**
     * UP/DOWN on the remote: the neighbouring channel inside the current group. Group edges clamp; a
     * channel that is not in the catalog any more does nothing.
     */
    fun switchChannel(delta: Int) {
        val currentId = playback.value.channelId ?: return
        val target = ChannelSwitchPlanner.neighbor(_order.value, currentId, delta) ?: return
        openChannel(target)
    }

    /** Digit jump (docs/02 §8.2 数字键跳台): the channel carrying the number the user typed. */
    fun jumpToNumber(number: Int) {
        val target = ChannelSwitchPlanner.byNumber(_order.value, number) ?: return
        openChannel(target)
    }

    private fun openChannel(target: Channel) {
        if (target.id == playback.value.channelId) return
        viewModelScope.launch {
            val item = channels.get(target.id)
            val stream = item?.streams?.firstOrNull()
            if (item == null || stream == null) {
                _fault.value = "「${target.name}」没有可用的流"
                return@launch
            }
            val fromChannelId = playback.value.channelId
            _fault.value = null
            request = PlayerContract.Input(channelId = target.id, streamId = stream.id, autoplay = true)
            loadNowNext(target.id)
            coordinator.open(item.channel, stream, switchedFromChannelId = fromChannelId)
        }
    }

    // ---------------------------------------------------------------- screen controls

    /** OK on the "画幅" control: next of the four §7.4 modes. */
    fun cycleAspectRatio(): AspectRatioMode {
        val next = AspectRatioCycle.next(playback.value.aspectRatio)
        session.setAspectRatio(next)
        return next
    }

    // ---------------------------------------------------------------- P3-3 tracks and overscan

    /**
     * P3-3 item 1: switch the audio track. The engine applies it on the running stream (no
     * re-prepare), so the change is heard immediately; the returned value reports whether the id was
     * still part of the stream.
     */
    fun selectAudioTrack(id: String?): Boolean = session.selectAudioTrack(id)

    /** P3-3 item 2: pick one subtitle track; `null` is the menu's 「关闭」 row. */
    fun selectSubtitleTrack(id: String?): Boolean = session.selectSubtitleTrack(id)

    /** P3-3 item 2: plain subtitle on/off, keeping the remembered track. */
    fun setSubtitlesEnabled(enabled: Boolean) = session.setSubtitlesEnabled(enabled)

    /**
     * P3-3 item 3: one overscan step (±). The ladder clamps at both ends (it is a step, not a cycle)
     * and the new index is written straight to the store, so the value survives a restart.
     */
    fun moveOverscan(delta: Int): Int {
        val next = OverscanPolicy.move(_overscanIndex.value, delta)
        overscan.levelIndex = next
        _overscanIndex.value = next
        return next
    }

    /** P3-3 item 3: back to 100 % — the escape hatch from a ladder the user got lost in. */
    fun resetOverscan(): Int {
        overscan.levelIndex = OverscanPolicy.DEFAULT_INDEX
        _overscanIndex.value = OverscanPolicy.DEFAULT_INDEX
        return OverscanPolicy.DEFAULT_INDEX
    }

    /** docs/02 §8.2: `KEYCODE_MEDIA_*` are the play/pause controls; a paused stream never stalls. */
    fun setPaused(paused: Boolean) {
        coordinator.setPaused(paused)
        if (paused) session.pause() else session.play()
    }

    fun attachSurface(surface: Surface?) = session.attachSurface(surface)

    /** The screen is gone for good: end the session, keep the engine instance (§7.2 P3/P5). */
    fun onPlayerClosed() {
        coordinator.stop(reason = "screen-closed")
    }

    override fun onCleared() {
        nowNextJob?.cancel()
        networkRetry.detach()
        coordinator.stop(reason = "view-model-cleared")
        super.onCleared()
    }

    /** docs/02 §8.2: an explicit起始流 wins, otherwise the repository's first candidate. */
    private fun selectStream(streams: List<Stream>, streamId: Long?): Stream? =
        streamId?.let { id -> streams.firstOrNull { it.id == id } } ?: streams.firstOrNull()

    /**
     * Resolves the channel's now/next and hands it to the session's info bar.
     *
     * Failure is silent on purpose: a channel without EPG, or a lookup that fails, is a normal state
     * (docs/02 §6.3 降级 "UI 只显示频道名"). It must never surface a player error — losing the
     * programme line is not losing playback, and a screen-level fault for it would be a lie.
     *
     * The result is applied only if the request still targets [channelId], so a switch that happens
     * while the query is in flight cannot put the previous channel's programme on screen.
     */
    private fun loadNowNext(channelId: Long) {
        nowNextJob?.cancel()
        nowNextJob = viewModelScope.launch {
            val nowNext = runCatching { epg.nowNext(channelId, clock.nowMs()) }.getOrNull()
            if (request?.channelId == channelId) session.onNowNext(nowNext)
        }
    }
}
