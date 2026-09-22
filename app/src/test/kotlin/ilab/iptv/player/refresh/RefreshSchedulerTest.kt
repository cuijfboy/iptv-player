package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import ilab.iptv.player.core.model.RefreshTrigger
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P2-5 item 1 + item 5: the schedule is queued with the configured time-of-day and every decision is
 * observable as one `WORK_SCHEDULE` line (docs/03 §3.3).
 */
class RefreshSchedulerTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val logger = RecordingLogger()
    private val enqueuer = RecordingWorkEnqueuer()
    private val ledger = FakeRefreshRunLedger()

    private val now: Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 22, 5, 30)
    }.timeInMillis

    private fun scheduler(settings: RefreshScheduleSettings) =
        RefreshScheduler(enqueuer, settings, logger, FakeClock(now), ledger, zone)

    @Test
    fun `the daily job lands on the next occurrence of the configured time`() {
        val spec = scheduler { 7 * 60 }.scheduleDaily()

        assertThat(spec.initialDelayMs).isEqualTo(90L * 60_000L)
        assertThat(enqueuer.periodic).hasSize(1)
        assertThat(enqueuer.once).isEmpty()
        assertThat(logger.count(EventCodes.WORK_SCHEDULE)).isEqualTo(1)
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["atMinuteOfDay"]).isEqualTo(7 * 60)
    }

    @Test
    fun `the shipped default is 06 00 and a garbage setting cannot move it`() {
        val spec = scheduler { RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY }.scheduleDaily()
        val garbage = scheduler { 99_999 }.scheduleDaily()

        assertThat(spec.initialDelayMs).isEqualTo(30L * 60_000L)
        assertThat(garbage.initialDelayMs).isEqualTo(spec.initialDelayMs)
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["atMinuteOfDay"])
            .isEqualTo(RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY)
    }

    @Test
    fun `a manual request queues the immediate job and says why`() = runTest {
        val spec = scheduler { RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY }
            .enqueueNow(RefreshTrigger.MANUAL)

        assertThat(spec.uniqueName).isEqualTo(RefreshWorkSpec.MANUAL_NAME)
        assertThat(enqueuer.once).hasSize(1)
        assertThat(enqueuer.periodic).isEmpty()
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["trigger"]).isEqualTo("MANUAL")
    }

    @Test
    fun `a re-trigger of a run the process death interrupted replaces the queued job`() = runTest {
        // NEW-004, the exact QA shape: the killed run came back as ENQUEUED in its 30 min backoff and
        // the mark on the ledger says why. `KEEP` would have swallowed this request.
        enqueuer.queued = ExistingRefreshRun(QueuedRefreshState.QUEUED)
        ledger.unconcluded = true

        scheduler { RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY }.enqueueNow(RefreshTrigger.MANUAL)

        assertThat(enqueuer.oncePolicies).containsExactly(RefreshEnqueuePolicy.REPLACE)
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["policy"]).isEqualTo("REPLACE")
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["queuedState"]).isEqualTo("QUEUED")
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["interruptedRunOnRecord"]).isEqualTo(true)
    }

    @Test
    fun `an ordinary queued retry keeps its backoff`() = runTest {
        // The same queued state, but no interruption on record: a run that ended and asked for a retry
        // itself must not have its backoff thrown away by a later request.
        enqueuer.queued = ExistingRefreshRun(QueuedRefreshState.QUEUED)
        ledger.unconcluded = false

        scheduler { RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY }.enqueueNow(RefreshTrigger.MANUAL)

        assertThat(enqueuer.oncePolicies).containsExactly(RefreshEnqueuePolicy.KEEP)
        assertThat(logger.fields(EventCodes.WORK_SCHEDULE)["interruptedRunOnRecord"]).isEqualTo(false)
    }

    @Test
    fun `rescheduling is idempotent from the caller's point of view`() {
        val scheduler = scheduler { RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY }

        scheduler.scheduleDaily()
        scheduler.scheduleDaily()

        assertThat(enqueuer.periodic).hasSize(2)
        assertThat(enqueuer.periodic.map { it.uniqueName }.distinct())
            .containsExactly(RefreshWorkSpec.PERIODIC_NAME)
        assertThat(logger.count(EventCodes.WORK_SCHEDULE)).isEqualTo(2)
    }
}
