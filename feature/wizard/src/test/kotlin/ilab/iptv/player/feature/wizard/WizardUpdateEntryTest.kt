package ilab.iptv.player.feature.wizard

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.wizard.WizardState
import ilab.iptv.player.core.domain.wizard.WizardStep
import ilab.iptv.player.core.domain.wizard.WizardUpdatePort
import ilab.iptv.player.core.domain.wizard.WizardUpdateResult
import ilab.iptv.player.core.domain.wizard.WizardUpdateRun
import ilab.iptv.player.core.domain.wizard.WizardUpdateSnapshot
import ilab.iptv.player.core.domain.wizard.WizardUpdateState
import ilab.iptv.player.core.domain.wizard.WizardUpdateWait
import ilab.iptv.player.core.model.RefreshPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 卡 WIZARD-BACK-1 — "回到第 2 步" must not mean "重新联网更新".
 *
 * The G7-1 round saw the wizard run the whole refresh twice (2 × `SRC_REFRESH_DONE`, ≈152 s each), and
 * tied the second one to coming back onto 更新. What this file pins down is the *decision* that made
 * that possible: the step's auto-start was taken from the screen's own state, whose "not started" value
 * also means "the port has not been read yet". The read is asynchronous (WorkManager answers from its
 * database), so a screen that arrives on 更新 — a resume on the remembered step, or BACK from 开看 —
 * could read "nothing asked for yet" for a round that was already queued or already finished.
 *
 * [ReadingWizardUpdatePort] therefore answers like the real one: its first reading lands a beat later.
 * That delay is the defect's precondition rather than a detail of the fake. `Dispatchers.Main` is an
 * `UnconfinedTestDispatcher`, and each test advances its scheduler to let the read land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardUpdateEntryTest {

    private val main = UnconfinedTestDispatcher()

    private val firstRun = FakeFirstRunStore()
    private val builtIns = FakeBuiltInCatalog()
    private val sources = FakeSourceManagementPort()
    private val channels = FakeChannelRepository()
    private val importPort = FakePlaylistImportPort()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(update: WizardUpdatePort): WizardViewModel =
        WizardViewModel(builtIns, sources, channels, importPort, update, firstRun)

    // ---- ① the defect: coming back onto 更新 must not enqueue a second round ----

    @Test
    fun `coming back onto 更新 with a finished round on record starts nothing`() {
        // The device shape: the wizard remembers the step it was left on, and the manual refresh job
        // it already ran is still readable (SUCCEEDED, 9 channels).
        firstRun.saved = WizardState(step = WizardStep.UPDATE)
        val update = ReadingWizardUpdatePort(completedRun())

        val wizard = model(update)
        main.scheduler.advanceUntilIdle()

        assertThat(wizard.uiState.value.step).isEqualTo(WizardStep.UPDATE)
        // Repair line: before the fix this read 1 — a second full refresh, ≈152 s of network work.
        assertThat(update.startCount).isEqualTo(0)
        // And the step shows the result it has, instead of pretending nothing ever ran.
        assertThat(wizard.uiState.value.update)
            .isEqualTo(WizardUpdateState.Done(okCount = 9, failCount = 0, partial = false))
    }

    @Test
    fun `BACK from 开看 to 更新 does not re-queue the round that already ran`() {
        val update = ReadingWizardUpdatePort()

        val wizard = model(update)
        wizard.onContinue()
        main.scheduler.advanceUntilIdle()
        assertThat(update.startCount).isEqualTo(1)

        // The run lands while the user is looking at the step (the device's ≈152 s run).
        update.land(completedRun())
        main.scheduler.advanceUntilIdle()
        assertThat(wizard.uiState.value.update)
            .isEqualTo(WizardUpdateState.Done(okCount = 9, failCount = 0, partial = false))

        wizard.onContinue()
        assertThat(wizard.uiState.value.step).isEqualTo(WizardStep.WATCH)

        // The G7-1 gesture.
        assertThat(wizard.onBack()).isTrue()
        main.scheduler.advanceUntilIdle()

        assertThat(wizard.uiState.value.step).isEqualTo(WizardStep.UPDATE)
        assertThat(update.startCount).isEqualTo(1)
    }

    @Test
    fun `a round that is already queued or running is not started again by entering the step`() {
        val queued = ReadingWizardUpdatePort(
            WizardUpdateSnapshot(run = WizardUpdateRun.QUEUED, wait = WizardUpdateWait.AWAITING_CONSTRAINTS),
        )
        val running = ReadingWizardUpdatePort(
            WizardUpdateSnapshot(run = WizardUpdateRun.RUNNING, phase = RefreshPhase.DEEP, done = 1, total = 4),
        )

        firstRun.saved = WizardState(step = WizardStep.UPDATE)
        model(queued)
        model(running)
        main.scheduler.advanceUntilIdle()

        assertThat(queued.startCount).isEqualTo(0)
        assertThat(running.startCount).isEqualTo(0)
    }

    // ---- ② the promise kept: a first entry still starts exactly one round ----

    @Test
    fun `the first entry into 更新 starts exactly one round, and walking back in adds none`() {
        val update = ReadingWizardUpdatePort()

        val wizard = model(update)
        wizard.onContinue()
        main.scheduler.advanceUntilIdle()

        assertThat(wizard.uiState.value.step).isEqualTo(WizardStep.UPDATE)
        assertThat(update.startCount).isEqualTo(1)

        // 选源 → 更新 again: the same round is still the one this session asked for.
        wizard.onBack()
        wizard.onContinue()
        main.scheduler.advanceUntilIdle()

        assertThat(update.startCount).isEqualTo(1)
    }

    @Test
    fun `resuming on 更新 with nothing on record still starts the run the user never got`() {
        firstRun.saved = WizardState(step = WizardStep.UPDATE)
        val update = ReadingWizardUpdatePort()

        model(update)
        main.scheduler.advanceUntilIdle()

        assertThat(update.startCount).isEqualTo(1)
    }

    // ---- ③ the user's own request is unchanged ----

    @Test
    fun `the button starts a run every time the user asks, finished or cancelled`() {
        firstRun.saved = WizardState(step = WizardStep.UPDATE)
        val update = ReadingWizardUpdatePort(completedRun())

        val wizard = model(update)
        main.scheduler.advanceUntilIdle()
        assertThat(update.startCount).isEqualTo(0) // the entry declined the finished round…

        wizard.startUpdate()
        main.scheduler.advanceUntilIdle()
        assertThat(update.startCount).isEqualTo(1) // …and 重试更新 still runs it

        update.land(WizardUpdateSnapshot(run = WizardUpdateRun.CANCELLED))
        main.scheduler.advanceUntilIdle()
        wizard.startUpdate()
        main.scheduler.advanceUntilIdle()
        assertThat(update.startCount).isEqualTo(2)
    }

    @Test
    fun `a run the user cancelled stays cancelled until they ask for it again`() {
        firstRun.saved = WizardState(step = WizardStep.UPDATE)
        val update = ReadingWizardUpdatePort(WizardUpdateSnapshot(run = WizardUpdateRun.CANCELLED))

        val wizard = model(update)
        main.scheduler.advanceUntilIdle()

        assertThat(wizard.uiState.value.update).isEqualTo(WizardUpdateState.Cancelled)
        assertThat(update.startCount).isEqualTo(0)
    }

    // ---- ⑤ NEW-004 regression: the interrupted run is still reclaimed on entry ----

    @Test
    fun `an attempt the process death interrupted is re-asked on entry, so it does not wait 30 min`() {
        // The wizard comes back onto 更新 and the job it left behind is ENQUEUED behind that attempt's
        // backoff. This is the one queued case that must re-ask: `enqueueNow` reclaims it (NEW-004).
        firstRun.saved = WizardState(step = WizardStep.UPDATE)
        val update = ReadingWizardUpdatePort(
            WizardUpdateSnapshot(run = WizardUpdateRun.QUEUED, wait = WizardUpdateWait.RETRY_AFTER_INTERRUPTION),
        )

        model(update)
        main.scheduler.advanceUntilIdle()

        assertThat(update.startCount).isEqualTo(1)
    }

    private fun completedRun(): WizardUpdateSnapshot = WizardUpdateSnapshot(
        run = WizardUpdateRun.SUCCEEDED,
        phase = RefreshPhase.DONE,
        done = 1,
        total = 1,
        result = WizardUpdateResult.COMPLETED,
        okCount = 9,
        failCount = 0,
    )
}

/**
 * The 更新 port as the device has it: WorkManager answers from its own database, so the first reading
 * lands *after* the screen exists. [readLatencyMs] is virtual time — each test advances the main
 * dispatcher's scheduler to let the read land, which keeps the ordering deterministic while still
 * modelling "the screen decided before the read arrived".
 */
private class ReadingWizardUpdatePort(
    private var reading: WizardUpdateSnapshot = WizardUpdateSnapshot(),
    private val readLatencyMs: Long = 5,
) : WizardUpdatePort {

    private val snapshots = MutableStateFlow(reading)

    var startCount: Int = 0
        private set

    override fun observe(): Flow<WizardUpdateSnapshot> = flow {
        delay(readLatencyMs)
        emitAll(snapshots)
    }

    override suspend fun start() {
        startCount++
    }

    override suspend fun cancel() = Unit

    /** The run this port is holding moves on, the way WorkManager would report it. */
    fun land(next: WizardUpdateSnapshot) {
        reading = next
        snapshots.value = next
    }
}
