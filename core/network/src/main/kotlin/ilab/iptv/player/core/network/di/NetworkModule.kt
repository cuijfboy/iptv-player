package ilab.iptv.player.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.network.OkHttpFetcher
import ilab.iptv.player.core.network.StreamingHttpFetcher
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * The one place an [OkHttpClient] is built. It lives in `:core:network` because that is the only
 * module allowed to see OkHttp (docs/02 §3.2 rule 5: the module is hidden behind `implementation`),
 * so the pipeline depends on the [HttpFetcher] interface and never on the transport.
 *
 * Timeouts here are only the client-level sanity net; the per-request timeout that docs/02 §6.1
 * pins comes from `PipelineLimits` and is set on each call by [OkHttpFetcher].
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideOkHttpFetcher(client: OkHttpClient, logger: Logger): OkHttpFetcher =
        OkHttpFetcher(client, logger)

    @Provides
    @Singleton
    fun provideHttpFetcher(fetcher: OkHttpFetcher): HttpFetcher = fetcher

    /**
     * The streaming half (P2-7). The same instance answers both interfaces: the transport, the
     * timeout and the retry policy are one decision, and `:core:epg` reads a multi-megabyte XMLTV body
     * through this without the byte cap of [provideHttpFetcher].
     */
    @Provides
    @Singleton
    fun provideStreamingHttpFetcher(fetcher: OkHttpFetcher): StreamingHttpFetcher = fetcher
}
