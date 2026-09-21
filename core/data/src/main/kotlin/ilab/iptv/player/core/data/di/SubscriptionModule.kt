package ilab.iptv.player.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ilab.iptv.player.core.common.DispatcherProvider
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.source.SubscriptionSourceProvider
import ilab.iptv.player.core.domain.repository.SourceRepository
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.provider.SourceProvider
import javax.inject.Singleton

/**
 * P2-6 正篇 item 2: the user's subscription URLs join the refresh pipeline through the *same*
 * extension point the 17 built-in aggregates use.
 *
 * `RefreshSourcesUseCase` consumes `Set<SourceProvider>`, so "a new kind of source" is one
 * `@IntoSet` binding and **zero** pipeline changes (docs/02 §9 E1). The built-ins are registered in
 * `:core:source`'s `SourceModule`; this one cannot live there because it needs `:core:domain`
 * (the `source` table) and `:core:data` is the module that may depend on both `:core:source` and
 * `:core:domain` (docs/02 §3.2 — `:core:source` may not see `:core:domain`).
 *
 * It is deliberately **one** provider, not one per row: a Hilt set is fixed at compile time, so a row
 * the user adds at runtime is fanned out inside
 * [ilab.iptv.player.core.data.source.SubscriptionSourceProvider.fetch] instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object SubscriptionModule {

    @Provides
    @IntoSet
    @Singleton
    fun subscriptionSourceProvider(
        sources: SourceRepository,
        status: SourceManagementPort,
        fetcher: HttpFetcher,
        logger: Logger,
        limits: PipelineLimits,
        dispatchers: DispatcherProvider,
    ): SourceProvider = SubscriptionSourceProvider(
        sources = sources,
        status = status,
        fetcher = fetcher,
        logger = logger,
        limits = limits,
        dispatchers = dispatchers,
    )
}
