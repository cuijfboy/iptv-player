package ilab.iptv.player.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.NowNext
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.refresh.FakeClock
import ilab.iptv.player.refresh.RecordingLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The one thing `:feature:settings` sees of P3-6: the port behind the panel's 「立即更新 EPG」button and
 * its EPG block. The test pins the two behaviours the panel depends on — a tap always reaches the
 * manual queue (never the freshness gate), and the status joins source freshness with coverage — so a
 * later refactor cannot quietly turn the button into a no-op.
 */
class EpgRefreshGatewayTest {

    private val clock = FakeClock()
    private val logger = RecordingLogger()
    private val status = FakeEpgSourceStatus()
    private val enqueuer = RecordingEpgWorkEnqueuer()

    private fun gateway(
        settings: EpgRefreshSettings = EpgRefreshSettings(),
        coverage: EpgCoverage = EpgCoverage(
            matched = 209,
            total = 658,
            byGroup = mapOf(ChannelGroup.CCTV to 80, ChannelGroup.SATELLITE to 69, ChannelGroup.HK_MO_TW to 4),
            byGroupTotal = mapOf(ChannelGroup.CCTV to 80, ChannelGroup.SATELLITE to 69, ChannelGroup.HK_MO_TW to 7),
        ),
    ) = EpgRefreshGateway(
        scheduler = EpgRefreshScheduler(
            enqueuer = enqueuer,
            policy = ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy(),
            settings = settings,
            status = status,
            guide = FakeEpgStoredGuide(),
            logger = logger,
            clock = clock,
        ),
        statusReader = status,
        repository = StubEpgRepository(coverage),
        settings = settings,
    )

    @Test
    fun `the button queues the manual job even when the stored guide is fresh`() = runTest {
        status.set(lastFetchAtMs = clock.nowMs() - 60_000L)

        val request = gateway().requestNow()

        assertThat(request.accepted).isTrue()
        assertThat(request.reason).isEqualTo("manual")
        assertThat(enqueuer.specs.single().uniqueName).isEqualTo(EpgRefreshWorkSpec.MANUAL_NAME)
    }

    @Test
    fun `with EPG switched off the button is honest about doing nothing`() = runTest {
        val request = gateway(settings = EpgRefreshSettings(enabled = false)).requestNow()

        assertThat(request.accepted).isFalse()
        assertThat(request.reason).isEqualTo("disabled")
        assertThat(enqueuer.specs).isEmpty()
    }

    @Test
    fun `the status joins the source table with the coverage of the current list`() = runTest {
        status.set(lastFetchAtMs = clock.nowMs() - 120_000L, lastResult = "OK:177447")

        val status = gateway().status()

        assertThat(status.sources.sources).isEqualTo(4)
        assertThat(status.sources.lastResult).isEqualTo("OK:177447")
        assertThat(status.coverage.matched).isEqualTo(209)
        assertThat(status.coverage.total).isEqualTo(658)
        assertThat(status.settings.minIntervalMs).isEqualTo(EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS)
        assertThat(status.enabled).isTrue()
    }

    private class StubEpgRepository(private val coverage: EpgCoverage) : EpgRepository {

        override fun observeWindow(query: EpgWindowQuery): Flow<List<Programme>> = flowOf(emptyList())

        override suspend fun nowNext(channelId: Long, atMs: Long): NowNext? = null

        override suspend fun coverage(): EpgCoverage = coverage

        override suspend fun replaceAll(epgChannelId: String, programmes: List<Programme>): Int = 0

        override suspend fun prune(keepFromMs: Long, keepToMs: Long): Int = 0
    }
}
