package ilab.iptv.player.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.epg.RoomEpgRepository
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.epg.BuiltInEpgSources
import ilab.iptv.player.core.epg.EpgAliases
import ilab.iptv.player.core.epg.EpgMatcher
import ilab.iptv.player.core.epg.EpgProvider
import ilab.iptv.player.core.epg.XmltvPullParser
import ilab.iptv.player.core.epg.XmltvHttpEpgProvider
import ilab.iptv.player.core.network.StreamingHttpFetcher
import ilab.iptv.player.core.source.normalize.Keys
import javax.inject.Singleton

/**
 * Wires the EPG pipeline (P2-7): the port implementation, the match chain, and the E4 providers.
 *
 * Two things this module exists to decide, both of which are one-line "why" answers that would be
 * invisible if they happened inside `LoadEpgUseCase`:
 *
 * - **the name key is `:core:source`'s** (`Keys::nameKey`), not `:core:epg`'s mirror. Tier 2 compares
 *   against `channel.name_key`, which the playlist normalizer wrote, so both sides must be the same
 *   function. `:core:epg` keeps its own copy only because the §3.2 matrix forbids it from seeing
 *   `:core:source`; `NameKeyParityTest` (in this module, which sees both) pins them together.
 * - **each built-in source is a `@IntoSet` binding** (§4.4 E4), so adding a guide is one provider
 *   class plus one line here — the refresh pipeline itself never changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object EpgModule {

    @Provides
    @Singleton
    fun provideEpgRepository(impl: RoomEpgRepository): EpgRepository = impl

    @Provides
    @Singleton
    fun provideEpgMatcher(): EpgMatcher = EpgMatcher(
        nameKey = Keys::nameKey,
        aliases = EpgAliases.BUILT_IN,
    )

    /**
     * The streaming parser the refresh uses. It is stateless — one instance parses one guide at a time
     * — and it had a no-arg default until P3-6 wired the pipeline into the production graph: until then
     * nothing injected `LoadEpgUseCase` at all, so Dagger never had to satisfy its constructor. Binding
     * it here (rather than in `:core:epg`, which has no Hilt module) keeps "one parser per process"
     * explicit now that the use case is reachable.
     */
    @Provides
    @Singleton
    fun provideXmltvPullParser(): XmltvPullParser = XmltvPullParser()

    @Provides
    @IntoSet
    @Singleton
    fun provideEpgPwEpg(fetcher: StreamingHttpFetcher, logger: Logger): EpgProvider =
        providerFor(BuiltInEpgSources.EPG_PW_CN, fetcher, logger)

    @Provides
    @IntoSet
    @Singleton
    fun provideEpgPwHkEpg(fetcher: StreamingHttpFetcher, logger: Logger): EpgProvider =
        providerFor(BuiltInEpgSources.EPG_PW_HK, fetcher, logger)

    @Provides
    @IntoSet
    @Singleton
    fun provideEpgPwTwEpg(fetcher: StreamingHttpFetcher, logger: Logger): EpgProvider =
        providerFor(BuiltInEpgSources.EPG_PW_TW, fetcher, logger)

    @Provides
    @IntoSet
    @Singleton
    fun provideEpgShare01Epg(fetcher: StreamingHttpFetcher, logger: Logger): EpgProvider =
        providerFor(BuiltInEpgSources.EPGSHARE01_HK, fetcher, logger)

    /** One descriptor → one provider; the catalogue is the single place an address is written down. */
    private fun providerFor(
        id: String,
        fetcher: StreamingHttpFetcher,
        logger: Logger,
    ): EpgProvider = BuiltInEpgSources.byId(id)?.let { descriptor ->
        XmltvHttpEpgProvider(
            id = descriptor.id,
            label = descriptor.label,
            url = descriptor.url,
            fetcher = fetcher,
            logger = logger,
        )
    } ?: error("EPG source $id is not in BuiltInEpgSources")
}
