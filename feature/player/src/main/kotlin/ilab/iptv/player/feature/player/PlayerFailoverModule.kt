package ilab.iptv.player.feature.player

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.domain.playback.DefaultFailoverPolicy
import ilab.iptv.player.core.domain.playback.FailoverLimits
import ilab.iptv.player.core.domain.playback.FailoverTuning
import ilab.iptv.player.core.domain.playback.PlaybackWatchdog
import ilab.iptv.player.core.domain.playback.WatchdogConfig
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.StreamRepository
import javax.inject.Singleton

/**
 * Wires the frozen policy of `:core:domain` into the player screen (P1-5).
 *
 * Every number here comes from ONE place so the two halves of the design cannot drift:
 * `PlaybackWatchdog.prepareTimeoutMs` mirrors `PlaybackRequest.timeoutMs` (12 s, docs/02 §4.2) and
 * its two progress thresholds mirror `FailoverLimits.stallThresholdMs` (§4.3) — the coupling the
 * P1-6 hand-off list calls out as checkpoints 3 and 4.
 *
 * SINGLETON SCOPE: the policy and the watchdog carry per-session bookkeeping (switch budget, permanent
 * demotions, on-demand re-probe budget, last sample). One playback session exists at a time — the
 * process-wide engine makes that true — and [PlaybackFailoverCoordinator.open] resets both when a new
 * session starts, which is exactly what `docs/02 §4.3` asks for.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerFailoverModule {

    @Provides
    @Singleton
    fun provideFailoverLimits(): FailoverLimits = FailoverLimits()

    @Provides
    @Singleton
    fun provideFailoverPolicy(): DefaultFailoverPolicy = DefaultFailoverPolicy(FailoverTuning())

    @Provides
    @Singleton
    fun providePlaybackWatchdog(clock: Clock, limits: FailoverLimits): PlaybackWatchdog = PlaybackWatchdog(
        clock = clock,
        config = WatchdogConfig(
            // §4.2/§7.3: the same 12 s the engine gives `prepare`.
            prepareTimeoutMs = PREPARE_TIMEOUT_MS,
            // §4.3: the watchdog cannot see `FailoverInput`, so it mirrors the stall threshold here.
            progressThresholdMs = limits.stallThresholdMs,
            bufferingThresholdMs = limits.stallThresholdMs,
        ),
    )

    @Provides
    @Singleton
    fun provideFailoverCatalog(
        channels: ChannelRepository,
        streams: StreamRepository,
    ): FailoverCatalog = RepositoryFailoverCatalog(channels, streams)

    /** docs/02 §4.2: `PlaybackRequest.timeoutMs` default; the two must agree (P1-6 checkpoint 3). */
    private const val PREPARE_TIMEOUT_MS = 12_000L
}
