package ilab.iptv.player.feature.wizard

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.ImportedPlaylist
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.wizard.WizardStep
import ilab.iptv.player.core.domain.wizard.WizardState
import ilab.iptv.player.core.domain.wizard.WizardUpdateResult
import ilab.iptv.player.core.domain.wizard.WizardUpdateRun
import ilab.iptv.player.core.domain.wizard.WizardUpdateSnapshot
import ilab.iptv.player.core.domain.wizard.WizardUpdateState
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * docs/04 P2-9 items 2–3, driven through the view model the screen renders: the three-step flow,
 * "每步都能跳过，跳过后的状态要明确", and the 无源 branch the dispatch asks to be tested.
 *
 * Everything here is JVM-only. The wizard deliberately keeps its decisions out of the activity (which
 * needs a device) and behind ports (which are fakes), so what is asserted below is the behaviour the
 * acceptance line describes rather than a click-through.
 *
 * `Dispatchers.Main` is an `UnconfinedTestDispatcher`, which makes `viewModelScope` and the two
 * `stateIn(Eagerly)` flows resolve synchronously — the same reason the production view model keeps its
 * cheap `combine` on the collector's context instead of hopping to `Dispatchers.Default`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {

    private val firstRun = FakeFirstRunStore()
    private val builtIns = FakeBuiltInCatalog()
    private val sources = FakeSourceManagementPort()
    private val channels = FakeChannelRepository()
    private val importPort = FakePlaylistImportPort()
    private val update = FakeWizardUpdatePort()

    private lateinit var viewModel: WizardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = WizardViewModel(builtIns, sources, channels, importPort, update, firstRun)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- step 1 · 选源 ----

    @Test
    fun `the wizard opens on the first step and lists every built-in source`() {
        val state = viewModel.uiState.value

        assertThat(state.step).isEqualTo(WizardStep.SOURCE)
        assertThat(state.stepNumber).isEqualTo(1)
        // "内置聚合源默认全开": the list is the catalogue, and nothing marks any of them off.
        assertThat(state.builtIns.map { it.label }).containsExactly("内置源一", "内置源二")
        assertThat(state.sourceSkipped).isFalse()
    }

    @Test
    fun `the step reports the subscriptions and the remembered import`() {
        val withSubscription = FakeSourceManagementPort(rows = listOf(managedSource("sub:a")))
        val imported = FakePlaylistImportPort(remembered = importedPlaylist("mine.m3u"))
        val model = WizardViewModel(builtIns, withSubscription, channels, imported, update, firstRun)

        assertThat(model.uiState.value.subscriptionCount).isEqualTo(1)
        assertThat(model.uiState.value.importedName).isEqualTo("mine.m3u")
    }

    // ---- steps 2 and 3 ----

    @Test
    fun `continue walks 选源 to 更新 to 开看 and starts the update exactly once`() {
        viewModel.onContinue()

        assertThat(viewModel.uiState.value.step).isEqualTo(WizardStep.UPDATE)
        assertThat(viewModel.uiState.value.stepNumber).isEqualTo(2)
        assertThat(update.startCount).isEqualTo(1)

        viewModel.onContinue()

        assertThat(viewModel.uiState.value.step).isEqualTo(WizardStep.WATCH)
        assertThat(viewModel.uiState.value.stepNumber).isEqualTo(3)
        // 开看 does not re-enqueue anything.
        assertThat(update.startCount).isEqualTo(1)
    }

    @Test
    fun `going back to 更新 after a finished run does not start a second one`() {
        viewModel.onContinue()
        update.snapshots.value = WizardUpdateSnapshot(
            run = WizardUpdateRun.SUCCEEDED,
            phase = RefreshPhase.DONE,
            result = WizardUpdateResult.COMPLETED,
            okCount = 9,
        )
        viewModel.onContinue()

        assertThat(viewModel.onBack()).isTrue()

        assertThat(viewModel.uiState.value.step).isEqualTo(WizardStep.UPDATE)
        assertThat(update.startCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.update)
            .isEqualTo(WizardUpdateState.Done(okCount = 9, failCount = 0, partial = false))
    }

    @Test
    fun `back from 更新 cancels a run that is still in flight`() {
        viewModel.onContinue()
        update.snapshots.value = WizardUpdateSnapshot(
            run = WizardUpdateRun.RUNNING,
            phase = RefreshPhase.DEEP,
            done = 1,
            total = 4,
        )

        assertThat(viewModel.onBack()).isTrue()

        assertThat(update.cancelCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.step).isEqualTo(WizardStep.SOURCE)
    }

    @Test
    fun `back from the first step leaves the wizard without recording anything`() {
        // The screen finishes the activity when this is false; no flag is written, which is what makes
        // an abandoned wizard come back on the next launch.
        assertThat(viewModel.onBack()).isFalse()
        assertThat(firstRun.completed).isFalse()
    }

    // ---- skips ----

    @Test
    fun `skipping 选源 is recorded and the update still starts`() {
        viewModel.onSkip()

        val state = viewModel.uiState.value
        assertThat(state.step).isEqualTo(WizardStep.UPDATE)
        assertThat(state.sourceSkipped).isTrue()
        // Skipping a step does not skip the work of the step it lands on.
        assertThat(update.startCount).isEqualTo(1)
    }

    @Test
    fun `skipping 更新 advances and leaves the step marked as skipped`() {
        viewModel.onContinue()
        update.snapshots.value = WizardUpdateSnapshot(run = WizardUpdateRun.CANCELLED)

        viewModel.onSkip()

        val state = viewModel.uiState.value
        assertThat(state.step).isEqualTo(WizardStep.WATCH)
        assertThat(state.updateSkipped).isTrue()
        // A skipped step is never re-started: the run above was cancelled and stays cancelled.
        assertThat(update.startCount).isEqualTo(1)
    }

    @Test
    fun `going back after a skip clears the skip so the steps do not report a half-truth`() {
        viewModel.onSkip()
        assertThat(viewModel.uiState.value.sourceSkipped).isTrue()

        viewModel.onBack()

        assertThat(viewModel.uiState.value.step).isEqualTo(WizardStep.SOURCE)
        assertThat(viewModel.uiState.value.sourceSkipped).isFalse()
    }

    // ---- the update states the screen renders ----

    @Test
    fun `a running update reports its phase and percentage`() {
        update.snapshots.value = WizardUpdateSnapshot(
            run = WizardUpdateRun.RUNNING,
            phase = RefreshPhase.SCORE,
            done = 3,
            total = 4,
        )

        assertThat(viewModel.uiState.value.update)
            .isEqualTo(WizardUpdateState.Running(RefreshPhase.SCORE, percent = 75))
    }

    @Test
    fun `a failed update carries the reason and the detail the screen turns into a sentence`() {
        update.snapshots.value = WizardUpdateSnapshot(
            run = WizardUpdateRun.FAILED,
            detail = "TimeoutException",
        )

        val state = viewModel.uiState.value.update
        assertThat(state).isInstanceOf(WizardUpdateState.Failed::class.java)
        assertThat((state as WizardUpdateState.Failed).detail).isEqualTo("TimeoutException")
    }

    // ---- the 无源 branch ----

    @Test
    fun `开看 says there is nothing to watch when the catalog is empty`() {
        moveToWatch()

        val empty = viewModel.uiState.value
        assertThat(empty.catalogLoaded).isTrue()
        assertThat(empty.channelCount).isEqualTo(0)
        assertThat(empty.hasNoChannels).isTrue()
    }

    @Test
    fun `开看 counts the channels and how many of them can play`() {
        // The middle row is the interesting one: zero streams means the wizard must not promise the
        // remote a picture there (the refresh keeps such channels on purpose, docs/01 F2).
        channels.channels.value = listOf(
            channelWithStreams(1, "央视一套", streamCount = 2),
            channelWithStreams(2, "空台", streamCount = 0),
            channelWithStreams(3, "央视二套", streamCount = 1),
        )

        moveToWatch()

        val state = viewModel.uiState.value
        assertThat(state.channelCount).isEqualTo(3)
        assertThat(state.playableCount).isEqualTo(2)
        assertThat(state.hasNoChannels).isFalse()
    }

    @Test
    fun `开看 follows a refresh that lands while the screen is open`() {
        moveToWatch()
        assertThat(viewModel.uiState.value.hasNoChannels).isTrue()

        // The 更新 step finished while the user sat on 开看: the count has to follow the catalog, not
        // the snapshot taken when the step opened.
        channels.channels.value = listOf(channelWithStreams(1, "央视一套", streamCount = 1))

        assertThat(viewModel.uiState.value.channelCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.hasNoChannels).isFalse()
    }

    // ---- import: 选源's own path to channels ----

    @Test
    fun `a successful import is reported and becomes the remembered playlist`() {
        viewModel.importCandidate(candidate())

        val state = viewModel.uiState.value
        assertThat(state.lastImport)
            .isEqualTo(WizardViewModel.ImportOutcome.Done("list.m3u", channels = 3, streams = 5))
        assertThat(state.importedName).isEqualTo("list.m3u")
    }

    @Test
    fun `a rejected import reports the port's own words`() {
        importPort.nextResult = ImportResult.Failed("SRC_PARSE_FAIL", "这个文件不像是播放列表")

        viewModel.importCandidate(candidate())

        assertThat(viewModel.uiState.value.lastImport)
            .isEqualTo(WizardViewModel.ImportOutcome.Failed("这个文件不像是播放列表"))
    }

    // ---- completion ----

    @Test
    fun `finishing the wizard writes the first-run flag`() {
        assertThat(firstRun.completed).isFalse()

        viewModel.complete()
        viewModel.complete()

        assertThat(firstRun.completed).isTrue()
    }

    // ---- NEW-1: the wizard remembers where it was left ----

    @Test
    fun `an abandoned run is remembered so the next launch resumes there`() {
        viewModel.onContinue()

        assertThat(firstRun.saved?.step).isEqualTo(WizardStep.UPDATE)
    }

    @Test
    fun `a saved step is where the wizard opens`() {
        firstRun.saved = WizardState(step = WizardStep.WATCH)

        val resumed = WizardViewModel(builtIns, sources, channels, importPort, update, firstRun)

        assertThat(resumed.uiState.value.step).isEqualTo(WizardStep.WATCH)
        assertThat(resumed.uiState.value.stepNumber).isEqualTo(3)
    }

    @Test
    fun `resuming on 更新 starts the run the user never got to finish`() {
        firstRun.saved = WizardState(step = WizardStep.UPDATE)

        val resumed = WizardViewModel(builtIns, sources, channels, importPort, update, firstRun)

        assertThat(resumed.uiState.value.step).isEqualTo(WizardStep.UPDATE)
        assertThat(update.startCount).isEqualTo(1)
    }

    @Test
    fun `resuming on 开看 starts nothing`() {
        firstRun.saved = WizardState(step = WizardStep.WATCH)

        WizardViewModel(builtIns, sources, channels, importPort, update, firstRun)

        assertThat(update.startCount).isEqualTo(0)
    }

    @Test
    fun `the saved step follows BACK too`() {
        viewModel.onContinue()
        viewModel.onBack()

        assertThat(firstRun.saved?.step).isEqualTo(WizardStep.SOURCE)
    }

    @Test
    fun `a skipped set is part of what is remembered`() {
        viewModel.onSkip()

        assertThat(firstRun.saved?.step).isEqualTo(WizardStep.UPDATE)
        assertThat(firstRun.saved?.skipped).containsExactly(WizardStep.SOURCE)
    }

    @Test
    fun `finishing clears the resume slot so a completed wizard is never resumed`() {
        viewModel.onContinue()
        assertThat(firstRun.saved).isNotNull()

        viewModel.complete()

        assertThat(firstRun.completed).isTrue()
        assertThat(firstRun.saved).isNull()
    }

    private fun moveToWatch() {
        viewModel.onContinue()
        viewModel.onContinue()
    }
}

private fun managedSource(id: String): ManagedSource = ManagedSource(
    id = id,
    label = "订阅",
    url = "https://example.invalid/list.m3u",
    kind = SourceKind.M3U,
    enabled = true,
    builtIn = false,
    lastFetchAtMs = null,
    lastResult = null,
    lastFailure = null,
    entryCount = null,
)

private fun importedPlaylist(name: String): ImportedPlaylist = ImportedPlaylist(
    name = name,
    sourceId = "local:$name",
    copiedPath = "/tmp/imports/$name",
    sizeBytes = 1,
    importedAtMs = 0,
)

private fun candidate(): ImportCandidate = ImportCandidate(
    path = "/tmp/playlists/list.m3u",
    name = "list.m3u",
    sizeBytes = 1,
    modifiedAtMs = 0,
)
