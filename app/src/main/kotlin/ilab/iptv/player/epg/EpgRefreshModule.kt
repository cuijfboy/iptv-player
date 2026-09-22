package ilab.iptv.player.epg

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.PlaybackActivity
import ilab.iptv.player.core.data.epg.EpgSourceStatusReader
import ilab.iptv.player.core.data.epg.EpgStoredGuideReader
import ilab.iptv.player.core.data.epg.LoadEpgUseCase
import ilab.iptv.player.core.data.epg.RoomEpgStoredGuideReader
import ilab.iptv.player.core.data.epg.RoomEpgSourceStatusReader
import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.domain.refresh.EpgRefreshPort
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.repository.EpgRepository
import javax.inject.Singleton

/**
 * P3-6 wiring: the settings, the pure policy, the runner, the coordinator, the WorkManager plumbing
 * and the panel's port.
 *
 * It lives in `:app` for the same reason [ilab.iptv.player.refresh.RefreshModule] does — `:app` is the
 * only module that may see `:core:data` and own platform entry points — and it binds the *same*
 * process-wide playback flag (`PlaybackActivity`) the source refresh reads, so both jobs obey one
 * definition of "the TV is busy".
 */
@Module
@InstallIn(SingletonComponent::class)
object EpgRefreshModule {

    @Provides
    @Singleton
    fun provideEpgRefreshSettings(): EpgRefreshSettings = EpgRefreshSettings()

    @Provides
    @Singleton
    fun provideEpgRefreshPolicy(): EpgRefreshPolicy = EpgRefreshPolicy()

    @Provides
    @Singleton
    fun provideEpgSourceStatusReader(impl: RoomEpgSourceStatusReader): EpgSourceStatusReader = impl

    /**
     * BUG-20260922-016: the gate's second input. It is a separate port from the freshness reader on
     * purpose — "how old" and "does it show anything" are different questions with different owners,
     * and the bug was reading only the first one.
     */
    @Provides
    @Singleton
    fun provideEpgStoredGuideReader(impl: RoomEpgStoredGuideReader): EpgStoredGuideReader = impl

    @Provides
    @Singleton
    fun provideEpgRunner(useCase: LoadEpgUseCase): EpgRunner = UseCaseEpgRunner(useCase)

    @Provides
    @Singleton
    fun provideEpgRefreshCoordinator(
        runner: EpgRunner,
        policy: EpgRefreshPolicy,
        settings: EpgRefreshSettings,
        status: EpgSourceStatusReader,
        guide: EpgStoredGuideReader,
        logger: Logger,
        clock: Clock,
    ): EpgRefreshCoordinator = EpgRefreshCoordinator(
        runner = runner,
        policy = policy,
        settings = settings,
        status = status,
        guide = guide,
        // The one place R7's signal is bound for the EPG run: the same flag `:core:source` reads for
        // the concurrency half of the source refresh.
        playback = EpgRefreshCoordinator.PlaybackProbe { PlaybackActivity.isActive() },
        clock = clock,
        logger = logger,
    )

    @Provides
    @Singleton
    fun provideEpgWorkEnqueuer(@ApplicationContext context: Context): EpgWorkEnqueuer =
        WorkManagerEpgEnqueuer.from(context)

    @Provides
    @Singleton
    fun provideEpgRefreshScheduler(
        enqueuer: EpgWorkEnqueuer,
        policy: EpgRefreshPolicy,
        settings: EpgRefreshSettings,
        status: EpgSourceStatusReader,
        guide: EpgStoredGuideReader,
        logger: Logger,
        clock: Clock,
    ): EpgRefreshScheduler = EpgRefreshScheduler(enqueuer, policy, settings, status, guide, logger, clock)

    @Provides
    @Singleton
    fun provideEpgRefreshPort(
        scheduler: EpgRefreshScheduler,
        status: EpgSourceStatusReader,
        repository: EpgRepository,
        settings: EpgRefreshSettings,
    ): EpgRefreshPort = EpgRefreshGateway(scheduler, status, repository, settings)
}
