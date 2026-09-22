package ilab.iptv.player.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.domain.refresh.EpgRefreshAction
import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.refresh.FakeClock
import ilab.iptv.player.refresh.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The enqueue half of P3-6: which trigger queues what, and — just as important — when it queues
 * nothing at all. Everything here is a plain JVM test (a recording enqueuer instead of WorkManager,
 * the same trade P2-5's `RefreshScheduleTest` makes).
 */
class EpgRefreshSchedulerTest {

    private val clock = FakeClock()
    private val logger = RecordingLogger()
    private val enqueuer = RecordingEpgWorkEnqueuer()
    private val status = FakeEpgSourceStatus()
    private val settings = EpgRefreshSettings()
    private val scheduler = EpgRefreshScheduler(
        enqueuer = enqueuer,
        policy = EpgRefreshPolicy(),
        settings = settings,
        status = status,
        logger = logger,
        clock = clock,
    )

    @Test
    fun `a cold start with no stored guide queues the background job`() = runTest {
        val decision = scheduler.request(RefreshTrigger.FIRST_RUN)

        assertThat(decision.action).isEqualTo(EpgRefreshAction.RUN)
        assertThat(enqueuer.specs).hasSize(1)
        val spec = enqueuer.specs.single()
        assertThat(spec.uniqueName).isEqualTo(EpgRefreshWorkSpec.BACKGROUND_NAME)
        assertThat(spec.trigger).isEqualTo(RefreshTrigger.FIRST_RUN)
        assertThat(spec.requiresNetwork).isTrue()
        assertThat(spec.requiresBatteryNotLow).isTrue()
        assertThat(spec.backoffMs).isEqualTo(EpgRefreshPolicy.DEFER_BACKOFF_MS)
        assertThat(spec.maxAttempts).isEqualTo(EpgRefreshWorkSpec.MAX_ATTEMPTS)
        assertThat(spec.linearBackoff).isTrue()
        assertThat(spec.replaceExisting).isFalse()

        val event = logger.fields(EventCodes.WORK_SCHEDULE)
        assertThat(event["job"]).isEqualTo("epg")
        assertThat(event["decision"]).isEqualTo("RUN")
        assertThat(event["reason"]).isEqualTo(EpgRefreshPolicy.REASON_STALE)
        assertThat(event["name"]).isEqualTo(EpgRefreshWorkSpec.BACKGROUND_NAME)
    }

    @Test
    fun `a fresh dataset is not queued at all, and says why`() = runTest {
        status.set(lastFetchAtMs = clock.nowMs() - 60_000L, lastResult = "OK:25234")

        val decision = scheduler.request(RefreshTrigger.FIRST_RUN)

        assertThat(decision.action).isEqualTo(EpgRefreshAction.SKIP)
        assertThat(decision.reason).isEqualTo(EpgRefreshPolicy.REASON_FRESH)
        assertThat(enqueuer.specs).isEmpty()

        val event = logger.fields(EventCodes.WORK_SCHEDULE)
        assertThat(event["decision"]).isEqualTo("SKIP")
        assertThat(event["ageMs"]).isEqualTo(60_000L)
        assertThat(event["minIntervalMs"]).isEqualTo(EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS)
    }

    @Test
    fun `the post-refresh follow-up uses the same background queue as the cold start`() = runTest {
        scheduler.request(RefreshTrigger.SCHEDULED)

        assertThat(enqueuer.specs.single().uniqueName).isEqualTo(EpgRefreshWorkSpec.BACKGROUND_NAME)
        assertThat(enqueuer.specs.single().trigger).isEqualTo(RefreshTrigger.SCHEDULED)
    }

    @Test
    fun `the user's button queues its own job, which replaces a pending one and skips the battery gate`() = runTest {
        status.set(lastFetchAtMs = clock.nowMs() - 60_000L) // fresh: the manual trigger must ignore that

        val decision = scheduler.request(RefreshTrigger.MANUAL)

        assertThat(decision.action).isEqualTo(EpgRefreshAction.RUN)
        val spec = enqueuer.specs.single()
        assertThat(spec.uniqueName).isEqualTo(EpgRefreshWorkSpec.MANUAL_NAME)
        assertThat(spec.replaceExisting).isTrue()
        assertThat(spec.requiresBatteryNotLow).isFalse()
        assertThat(spec.requiresNetwork).isTrue()
    }

    @Test
    fun `the two queues are distinct, so a tap is never swallowed by a pending background run`() {
        assertThat(EpgRefreshWorkSpec.MANUAL_NAME).isNotEqualTo(EpgRefreshWorkSpec.BACKGROUND_NAME)
        assertThat(EpgRefreshWorkSpec.BACKGROUND_TRIGGERS).doesNotContain(RefreshTrigger.MANUAL)
    }
}
