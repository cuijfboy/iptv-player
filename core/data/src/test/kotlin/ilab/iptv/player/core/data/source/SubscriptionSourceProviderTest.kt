package ilab.iptv.player.core.data.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.refresh.RecordingLogger
import ilab.iptv.player.core.domain.repository.SourceRepository
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceFetchResult
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.domain.source.SourceMutation
import ilab.iptv.player.core.model.SourceConfig
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.network.HttpResponse
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * P2-6 正篇 item 2: the user's subscription rows join the refresh pipeline as one provider.
 *
 * The pipeline itself is frozen, so what is tested here is the *fan-out*: which rows are fetched, that
 * one broken row cannot take the others down, that each row records its own result (which is what the
 * settings screen shows), and that `channel.source_id` points back at the row.
 */
class SubscriptionSourceProviderTest {

    private val logger = RecordingLogger()
    private val clock = FakeClock()

    @Test
    fun `only enabled rows are fetched, and each one is fetched from its own url`() = test {
        val store = FakeSourceStore(
            row("sub:on", "https://on.example/list.m3u", enabled = true),
            row("sub:off", "https://off.example/list.m3u", enabled = false),
        )
        val fetcher = FakeFetcher { request ->
            assertThat(request.url).isEqualTo("https://on.example/list.m3u")
            body(M3U)
        }

        val result = provider(store, fetcher).fetch(clock)

        val entries = (result as AppResult.Ok).value
        assertThat(entries.map { it.name }).containsExactly("CCTV-1").inOrder()
        // The rows carry their own source id, so a stream traces back to the subscription it came from.
        assertThat(entries.map { it.sourceId }.toSet()).containsExactly("sub:on")
        assertThat(store.outcomes.map { it.id }).containsExactly("sub:on")
    }

    @Test
    fun `each attempt records its own last result and entry count`() = test {
        val store = FakeSourceStore(
            row("sub:ok", "https://ok.example/list.m3u", enabled = true),
            row("sub:bad", "https://bad.example/list.m3u", enabled = true),
        )
        val fetcher = FakeFetcher { request ->
            if (request.url.contains("bad")) AppResult.Err(AppError.timeout(EventCodes.NET_REQ_FAIL)) else body(M3U)
        }

        provider(store, fetcher).fetch(clock)

        val ok = store.outcomes.single { it.id == "sub:ok" }
        val bad = store.outcomes.single { it.id == "sub:bad" }
        assertThat(ok.ok).isTrue()
        assertThat(ok.entryCount).isEqualTo(1)
        assertThat(bad.ok).isFalse()
        assertThat(bad.detail).isEqualTo("TIMEOUT")
        assertThat(store.lastResult("sub:ok")).isEqualTo(SourceFetchResult.OK)
        assertThat(store.lastResult("sub:bad")).isEqualTo(SourceFetchResult.FAILED)
    }

    @Test
    fun `one broken subscription does not stop the others`() = test {
        val store = FakeSourceStore(
            row("sub:bad", "https://bad.example/list.m3u", enabled = true),
            row("sub:good", "https://good.example/list.m3u", enabled = true),
        )
        val fetcher = FakeFetcher { request ->
            if (request.url.contains("bad")) AppResult.Err(AppError.http(404, EventCodes.NET_REQ_FAIL))
            else body(TXT)
        }

        val result = provider(store, fetcher).fetch(clock)

        val entries = (result as AppResult.Ok).value
        assertThat(entries.map { it.name }).containsExactly("CCTV-2")
        // The failing row is visible in the log even though the run succeeded as a whole.
        assertThat(logger.count(EventCodes.SRC_FETCH_FAIL)).isEqualTo(1)
    }

    @Test
    fun `a body that parses to nothing is a successful fetch with zero entries`() = test {
        val store = FakeSourceStore(
            row("sub:weird", "https://weird.example/list.m3u", enabled = true),
            row("sub:good", "https://good.example/list.m3u", enabled = true),
        )
        val fetcher = FakeFetcher { request ->
            if (request.url.contains("weird")) body("%PDF-1.4 not a playlist") else body(M3U)
        }

        val result = provider(store, fetcher).fetch(clock)

        assertThat((result as AppResult.Ok).value.map { it.name }).containsExactly("CCTV-1")
        // Parser tolerant by design (docs/02 §6.1: unknown rows are skipped, not fatal): the list
        // retrieved fine and simply carried no usable row. The row status says "0 条", which is the
        // readable distinction between "source is down" and "source changed shape".
        assertThat(store.lastResult("sub:weird")).isEqualTo(SourceFetchResult.OK)
        assertThat(store.outcomes.single { it.id == "sub:weird" }.entryCount).isEqualTo(0)
    }

    @Test
    fun `every subscription failing reports a failed source`() = test {
        val store = FakeSourceStore(
            row("sub:a", "https://a.example/list.m3u", enabled = true),
            row("sub:b", "https://b.example/list.m3u", enabled = true),
        )
        val fetcher = FakeFetcher { AppResult.Err(AppError.timeout(EventCodes.NET_REQ_FAIL)) }

        val result = provider(store, fetcher).fetch(clock)

        assertThat(result).isInstanceOf(AppResult.Err::class.java)
    }

    @Test
    fun `no subscriptions is an empty success and touches no network`() = test {
        val store = FakeSourceStore(row("sub:off", "https://off.example/list.m3u", enabled = false))
        val fetcher = FakeFetcher { error("no request expected") }

        val result = provider(store, fetcher).fetch(clock)

        assertThat((result as AppResult.Ok).value).isEmpty()
        assertThat(fetcher.requests).isEmpty()
        assertThat(store.outcomes).isEmpty()
    }

    @Test
    fun `the provider presents itself as one registered source`() {
        val provider = provider(FakeSourceStore(), FakeFetcher { body(M3U) })

        assertThat(provider.id).isEqualTo(SubscriptionSourceProvider.ID)
        assertThat(provider.label).isEqualTo(SubscriptionSourceProvider.LABEL)
        assertThat(provider.kind).isEqualTo(SourceKind.M3U)
    }

    // --- fixtures --------------------------------------------------------------------------------

    private fun provider(store: FakeSourceStore, fetcher: FakeFetcher) = SubscriptionSourceProvider(
        sources = store,
        status = store,
        fetcher = fetcher,
        logger = logger,
        limits = PipelineLimits(),
        dispatchers = TestDispatcherProvider(),
    )

    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun body(text: String) = AppResult.Ok(
        HttpResponse(status = 200, bytes = text.toByteArray(), contentType = "text/plain", elapsedMs = 5, truncated = false),
    )

    private fun row(id: String, url: String, enabled: Boolean) = SourceConfig(
        id = id,
        providerId = id,
        label = id,
        url = url,
        kind = SourceKind.M3U,
        enabled = enabled,
        builtIn = false,
    )

    private class FakeFetcher(private val handler: (HttpRequest) -> AppResult<HttpResponse>) : HttpFetcher {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun fetch(request: HttpRequest): AppResult<HttpResponse> {
            requests += request
            return handler(request)
        }
    }

    /** Records what a fetch produced, so the assertions can read it back as the screen would. */
    private class Outcome(val id: String, val ok: Boolean, val entryCount: Int, val detail: String?)

    /** One stand-in for both faces of the `source` table. */
    private class FakeSourceStore(vararg rows: SourceConfig) : SourceRepository, SourceManagementPort {

        private val configs = rows.toMutableList()
        private val results = mutableMapOf<String, Pair<SourceFetchResult, String?>>()
        val outcomes = mutableListOf<Outcome>()

        fun lastResult(id: String): SourceFetchResult? = results[id]?.first

        override suspend fun all(): List<SourceConfig> = configs.toList()

        override suspend fun upsert(cfg: SourceConfig) {
            configs.removeAll { it.id == cfg.id }
            configs += cfg
        }

        override suspend fun remove(id: String) {
            configs.removeAll { it.id == id }
        }

        override suspend fun list(): List<ManagedSource> = configs.map {
            ManagedSource(
                id = it.id,
                label = it.label,
                url = it.url,
                kind = it.kind,
                enabled = it.enabled,
                builtIn = it.builtIn,
                lastFetchAtMs = null,
                lastResult = results[it.id]?.first,
                lastFailure = results[it.id]?.second,
                entryCount = null,
            )
        }

        override suspend fun add(draft: SourceDraft): SourceMutation = unsupported()

        override suspend fun update(id: String, draft: SourceDraft): SourceMutation = unsupported()

        override suspend fun setEnabled(id: String, enabled: Boolean): SourceMutation = unsupported()

        override suspend fun delete(id: String): SourceMutation = unsupported()

        override suspend fun recordOutcome(id: String, atMs: Long, ok: Boolean, entryCount: Int, detail: String?) {
            outcomes += Outcome(id, ok, entryCount, detail)
            results[id] = (if (ok) SourceFetchResult.OK else SourceFetchResult.FAILED) to detail
        }

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by this test")
    }

    private companion object {
        val M3U = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1
            http://a.example/cctv1/index.m3u8
        """.trimIndent()

        val TXT = """
            央视,#genre#
            CCTV-2,http://a.example/txt2/index.m3u8
        """.trimIndent()
    }
}
