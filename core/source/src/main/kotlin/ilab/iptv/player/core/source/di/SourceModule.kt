package ilab.iptv.player.core.source.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.source.deep.DeepProbeValidator
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import ilab.iptv.player.core.source.provider.BuiltInSources
import ilab.iptv.player.core.source.provider.RemotePlaylistSourceProvider
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import ilab.iptv.player.core.source.validate.ShallowReachabilityValidator
import javax.inject.Singleton

/**
 * Registers every built-in source through Hilt `@IntoSet` (docs/02 §9 E1/E2, §4.4 extension points).
 *
 * The pipeline consumes `Set<SourceProvider>` / `Set<StreamValidator>`, so **adding an aggregate is
 * adding one catalogue row plus one binding below** — no pipeline code changes; a third party can
 * add one from another module by binding their own provider into the same set.
 */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun providePipelineLimits(): PipelineLimits = PipelineLimits()

    /**
     * R7 seam (docs/02 §4.5 C3). Until P1-4 exposes the real playback state this reports "not
     * playing", so concurrency is never halved; the wiring is a one-line change in the provider once
     * `PlaybackController.state` exists. Tracked as an open item in the P2-4a report.
     */
    @Provides
    @Singleton
    fun providePlaybackPrioritySignal(): PlaybackPrioritySignal = PlaybackPrioritySignal { false }

    @Provides
    @IntoSet
    @Singleton
    fun guovinResult(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.GUOVIN_RESULT, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun guovinIpv4(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.GUOVIN_IPV4, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun fanmingmingItv(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.FANMINGMING_ITV, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun fanmingmingIndex(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.FANMINGMING_INDEX, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun yangGather(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.YANG_GATHER, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun yangMigu(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.YANG_MIGU, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun hujingguangCn(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.HUJINGGUANG_CN, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun bestfanCnAll(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.BESTFAN_CN_ALL, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun bestfanCnCctv(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.BESTFAN_CN_CCTV, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun bestfanCnProvince(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.BESTFAN_CN_PROVINCE, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun akiralerealIptv(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.AKIRALEREAL_IPTV, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun evilcultHttp(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.EVILCULT_HTTP, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun vbskycnIptv4(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.VBSKYCN_IPTV4, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun ngo5Ipv4(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.NGO5_IPV4, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun suppriseLive(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.SUPPRISE_LIVE, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun kimwangOthers(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.KIMWANG_OTHERS, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun suxuangIpv4(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): SourceProvider =
        builtIn(BuiltInSources.SUXUANG_IPV4, fetcher, logger, limits)

    @Provides
    @IntoSet
    @Singleton
    fun shallowReachability(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): StreamValidator =
        ShallowReachabilityValidator(fetcher, logger, limits)

    /**
     * The DEEP stage (docs/02 §6.1, P2-4b). No [ilab.iptv.player.core.source.deep.TrackProbe] is
     * bound yet: real track decoding needs Media3 and a device, so the probe reports the codecs an
     * HLS playlist declares (`codecSource = "declared"`). Binding an engine-backed `TrackProbe` is
     * the one-line upgrade the P2-4b report tracks; the probe then reports `codecSource = "probe"`.
     */
    @Provides
    @IntoSet
    fun deepProbe(fetcher: HttpFetcher, logger: Logger, limits: PipelineLimits): StreamValidator =
        DeepProbeValidator(fetcher = fetcher, logger = logger, limits = limits)

    private fun builtIn(
        id: String,
        fetcher: HttpFetcher,
        logger: Logger,
        limits: PipelineLimits,
    ): SourceProvider = RemotePlaylistSourceProvider(
        descriptor = requireNotNull(BuiltInSources.byId(id)) { "unknown built-in source: $id" },
        fetcher = fetcher,
        logger = logger,
        limits = limits,
    )
}
