package ilab.iptv.player.refresh

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.PlaybackActivity
import ilab.iptv.player.core.data.refresh.RefreshSourcesUseCase
import ilab.iptv.player.core.domain.refresh.PlaybackAvoidancePolicy
import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import javax.inject.Singleton

/**
 * P2-5 wiring: the schedule setting, the avoidance policy and the WorkManager plumbing.
 *
 * It lives in `:app` because that is the only module allowed to assemble the pieces (§3.2: `:app`
 * may see `:core:data` and the features, and it owns the platform entry points), and because the
 * scheduling half is a platform concern — the pipeline itself is untouched by this round.
 */
@Module
@InstallIn(SingletonComponent::class)
object RefreshModule {

    /**
     * The refresh time as a setting (docs/04 P2-5 item 1). P2-8's settings page will provide the real
     * one; until then the default is 06:00 by design, not by omission.
     */
    @Provides
    @Singleton
    fun provideRefreshScheduleSettings(): RefreshScheduleSettings =
        RefreshScheduleSettings { RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY }

    @Provides
    @Singleton
    fun providePlaybackAvoidancePolicy(): PlaybackAvoidancePolicy = PlaybackAvoidancePolicy()

    @Provides
    @Singleton
    fun provideRefreshRunner(useCase: RefreshSourcesUseCase): RefreshRunner = UseCaseRefreshRunner(useCase)

    @Provides
    @Singleton
    fun provideRefreshRunCoordinator(
        runner: RefreshRunner,
        logger: Logger,
        policy: PlaybackAvoidancePolicy,
        clock: Clock,
    ): RefreshRunCoordinator = RefreshRunCoordinator(
        runner = runner,
        logger = logger,
        policy = policy,
        // The one place the R7 signal is bound for the scheduling half: the same process-wide flag
        // `:core:source` reads for the concurrency half.
        playback = RefreshRunCoordinator.PlaybackProbe { PlaybackActivity.isActive() },
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideWorkEnqueuer(@ApplicationContext context: Context): WorkEnqueuer =
        WorkManagerEnqueuer.from(context)

    @Provides
    @Singleton
    fun provideRefreshScheduler(
        enqueuer: WorkEnqueuer,
        settings: RefreshScheduleSettings,
        logger: Logger,
        clock: Clock,
    ): RefreshScheduler = RefreshScheduler(enqueuer, settings, logger, clock)
}
