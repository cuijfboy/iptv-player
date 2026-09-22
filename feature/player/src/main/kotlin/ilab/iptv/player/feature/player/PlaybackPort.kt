package ilab.iptv.player.feature.player

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.model.PreparedMedia
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth
import ilab.iptv.player.core.player.EngineSample
import ilab.iptv.player.core.player.PlaybackSession
import kotlinx.coroutines.flow.StateFlow

/**
 * What the fail-over wiring needs from playback (P1-5).
 *
 * The coordinator talks to this, not to [PlaybackSession], for one reason: the wiring is the part
 * this work package must *prove with unit tests* (which policy call comes after which failure), and
 * `PlaybackSession` is Android-bound (Context + HandlerThread + ExoPlayer) so it cannot be driven on
 * the JVM. [SessionPlaybackPort] is the one adapter that turns a session into this port.
 *
 * C1 is preserved: the state flow below is a READ channel; every write still happens inside the
 * session's `PlaybackUiStateMachine`.
 */
interface PlaybackPort {

    /** The single source of truth the UI renders (docs/02 §4.5 C1). */
    val state: StateFlow<PlaybackUiState>

    /**
     * Prepare [stream] of [channel] on the ONE engine instance (§7.2 P1: reuse, never rebuild).
     *
     * [timeoutMs] is the start-up window of this one attempt (§4.2 `PlaybackRequest.timeoutMs`);
     * SWITCH-P95-1 passes a shorter one for the first attempt of a channel that has a backup. `0`
     * means "the engine's own `EngineTuning.prepareTimeoutMs`".
     */
    suspend fun watch(
        channel: Channel,
        stream: Stream,
        attempt: Int,
        preferPassthrough: Boolean,
        timeoutMs: Long = 0L,
    ): AppResult<PreparedMedia>

    /** Live position/buffering read for the watchdog (§6.2). */
    suspend fun sample(): EngineSample

    fun stop(reason: String)

    /** A fail-over decision is being applied (`PlaybackPhase.FAILOVER` + info-bar hint). */
    fun onFailoverRunning(hint: String)

    /** The switch took effect. */
    fun onFailoverSwitched(toStreamId: Long, hint: String)

    /** No candidate left; the screen shows [message]. */
    fun onFailoverExhausted(error: AppError?, message: String)
}

/** The only production implementation: the process-wide session, seen through the port above. */
class SessionPlaybackPort(private val session: PlaybackSession) : PlaybackPort {

    override val state: StateFlow<PlaybackUiState> get() = session.state

    override suspend fun watch(
        channel: Channel,
        stream: Stream,
        attempt: Int,
        preferPassthrough: Boolean,
        timeoutMs: Long,
    ): AppResult<PreparedMedia> =
        session.watch(
            channel,
            stream,
            attempt = attempt,
            preferPassthrough = preferPassthrough,
            timeoutMs = timeoutMs,
        )

    override suspend fun sample(): EngineSample = session.sample()

    override fun stop(reason: String) = session.stop(reason)

    override fun onFailoverRunning(hint: String) = session.onFailoverRunning(hint)

    override fun onFailoverSwitched(toStreamId: Long, hint: String) =
        session.onFailoverSwitched(toStreamId, hint)

    override fun onFailoverExhausted(error: AppError?, message: String) =
        session.onFailoverExhausted(error, message)
}

/**
 * Where the policy's input comes from: the channel's candidates and their rolling health
 * (docs/02 §4.3 `FailoverInput.candidates` / `.health`).
 */
interface FailoverCatalog {

    /** Candidates for one channel, already ordered by the §4.3 key (`StreamRepository.candidates`). */
    suspend fun candidates(channelId: Long): List<Stream>

    /** Rolling health per stream; an unknown id simply has no entry. */
    suspend fun health(streamIds: List<Long>): Map<Long, StreamHealth>

    /**
     * The on-demand single-channel re-check of `docs/01 F4` / `RefreshTrigger.ON_DEMAND_SINGLE_CHANNEL`,
     * which the `PLAYLIST_GONE` row spends once per session before giving up.
     *
     * HONEST LIMIT: the deep network re-probe of one channel is P2-4b (the refresh pipeline landed in
     * P2-4a). Until it exists this re-reads the store, which is what the repository can answer today —
     * if the catalog changed underneath us (another refresh persisted new streams), the fresh
     * candidates are picked up; if it did not, the channel is reported unavailable. The verification
     * file states this and the P2-4b hand-off.
     */
    suspend fun reprobe(channelId: Long): List<Stream>
}

/** Repository-backed catalog: `:feature:player` is allowed to see the domain ports (§3.2). */
class RepositoryFailoverCatalog(
    private val channels: ChannelRepository,
    private val streams: StreamRepository,
) : FailoverCatalog {

    override suspend fun candidates(channelId: Long): List<Stream> =
        streams.candidates(channelId).ifEmpty { channels.get(channelId)?.streams.orEmpty() }

    override suspend fun health(streamIds: List<Long>): Map<Long, StreamHealth> =
        streamIds.distinct().associateWith { streams.health(it) }

    override suspend fun reprobe(channelId: Long): List<Stream> =
        channels.get(channelId)?.streams.orEmpty()
}
