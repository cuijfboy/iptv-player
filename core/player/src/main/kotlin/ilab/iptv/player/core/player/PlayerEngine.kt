package ilab.iptv.player.core.player

import android.view.Surface
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.AudioTrackInfo
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.PlaybackEvent
import ilab.iptv.player.core.model.PlaybackRequest
import ilab.iptv.player.core.model.PreparedMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform engine port (docs/02 §4.4, frozen interface v1) — extension point E3.
 *
 * Threading contract (docs/02 §4.5 C2): exactly ONE engine instance per process; `PlaybackController`
 * is the only caller; every member runs on `DispatcherProvider.engine`, a single-parallelism
 * dispatcher, and `prepare` is additionally mutex-guarded. The engine never switches sources and
 * never writes `PLAY_*` logs — it reports [state] and [events] and the controller decides
 * (docs/02 §6.2, §7.3).
 *
 * Dispatcher requirement (measured, see docs/05 §12): [PlayerEngineFactory.create] requires a
 * **Looper-backed** dispatcher (e.g. `Handler(handlerThread.looper).asCoroutineDispatcher()`),
 * because ExoPlayer insists on being driven from its application looper and this is that looper.
 */
interface PlayerEngine {
    val id: String
    val capabilities: Set<EngineCapability>
    val state: StateFlow<EngineState>
    val events: SharedFlow<PlaybackEvent>

    /** Only `PlaybackController` calls this, on the engine dispatcher (docs/02 §7.2 P3). `null` = detach. */
    fun attach(surface: Surface?)

    /** docs/02 §7.3: mutex-guarded, cancellable, `request.timeoutMs` (default 12 s) caps the wait. */
    suspend fun prepare(request: PlaybackRequest): AppResult<PreparedMedia>

    fun play()
    fun pause()
    fun stop()
    fun release()
    fun setAspectRatio(mode: AspectRatioMode)

    /** Cached, thread-safe snapshot of the current audio tracks (safe to call off the engine thread). */
    fun audioTracks(): List<AudioTrackInfo>

    /** Returns whether [id] exists; applying it is asynchronous, like the other commands. */
    fun selectAudioTrack(id: String?): Boolean
}

interface PlayerEngineFactory {
    val id: String

    /**
     * What this engine can *attempt* (docs/02 §7.1 selection key). Capabilities that depend on the
     * actual device (e.g. AC3 passthrough) are published by [PlayerEngine.capabilities] after the
     * device probe (§7.4) — matching is deliberately optimistic here and verified at runtime.
     */
    val caps: Set<EngineCapability>

    fun create(dispatcher: CoroutineDispatcher): PlayerEngine
}

/** docs/02 §4.4 / §7.1: pick the first factory whose `caps ⊇ required`. */
interface EngineSelector {
    fun select(required: Set<EngineCapability>, device: DeviceProfile): PlayerEngineFactory
}
