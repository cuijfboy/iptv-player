package ilab.iptv.player.feature.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.domain.wizard.BuiltInSourceCatalog
import ilab.iptv.player.core.domain.wizard.BuiltInSourceInfo
import ilab.iptv.player.core.domain.wizard.FirstRunStore
import ilab.iptv.player.core.domain.wizard.WizardFlow
import ilab.iptv.player.core.domain.wizard.WizardState
import ilab.iptv.player.core.domain.wizard.WizardStep
import ilab.iptv.player.core.domain.wizard.WizardUpdatePort
import ilab.iptv.player.core.domain.wizard.WizardUpdateReading
import ilab.iptv.player.core.domain.wizard.WizardUpdateState
import ilab.iptv.player.core.model.ChannelFilter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The first-run wizard's state (docs/04 P2-9 items 1–3).
 *
 * Everything the screen renders is derived here from four inputs and one state machine, so the
 * behaviour the card asks to be testable — the step machine, the skip branches and the no-source
 * branch — is a unit test rather than a click-through on a TV:
 *
 * | input | what it decides |
 * |---|---|
 * | [WizardFlow] state | which step is on screen and which steps were skipped |
 * | [SourceFacts] | the 选源 list: the built-ins (all on), the subscriptions, the last import |
 * | [CatalogFacts] | the 开看 promise: how many channels there are, how many can play |
 * | [WizardUpdateState] | the 更新 progress, outcome and retry |
 *
 * **It owns no pipeline.** 更新 goes through [WizardUpdatePort], whose production implementation
 * enqueues the same manual refresh job everything else uses; the wizard only reads it.
 */
@HiltViewModel
class WizardViewModel @Inject constructor(
    private val builtIns: BuiltInSourceCatalog,
    private val sources: SourceManagementPort,
    private val channels: ChannelRepository,
    private val importPort: PlaylistImportPort,
    private val update: WizardUpdatePort,
    private val firstRun: FirstRunStore,
) : ViewModel() {

    /** What the 选源 step shows besides the built-in list itself. */
    data class SourceFacts(
        val builtIns: List<BuiltInSourceInfo> = emptyList(),
        val subscriptionCount: Int = 0,
        /** The remembered local import's name, or null when there is none. */
        val importedName: String? = null,
        /** The outcome of the last import *this session* asked for, so the step can report it. */
        val lastImport: ImportOutcome? = null,
    )

    /** The result of one import, in the two shapes the step reports. */
    sealed interface ImportOutcome {
        data class Done(val name: String, val channels: Int, val streams: Int) : ImportOutcome

        /** [message] is already user-facing prose (it comes from the import port). */
        data class Failed(val message: String) : ImportOutcome
    }

    /** How much there is to watch — the 开看 step's promise and its no-source branch. */
    data class CatalogFacts(
        val channelCount: Int = 0,
        /** Channels with at least one stream: what "第一个可播放频道" can actually land on. */
        val playableCount: Int = 0,
        val loaded: Boolean = false,
    )

    /**
     * NEW-1: the wizard resumes where it was left. The read is the same synchronous
     * `SharedPreferences` read the router already does, so this costs no loading frame; a stored step
     * that cannot be parsed reads as "no progress" (see `SharedPrefsFirstRunStore`).
     */
    private val flow = MutableStateFlow(firstRun.savedProgress() ?: WizardState())

    private val sourceFacts = MutableStateFlow(SourceFacts(builtIns = builtIns.sources()))

    private val updateState: StateFlow<WizardUpdateState> = update.observe()
        .map(WizardUpdateReading::of)
        .stateIn(viewModelScope, SharingStarted.Eagerly, WizardUpdateState.NotStarted)

    /**
     * The channel catalog, **observed** rather than sampled: a refresh running on the 更新 step adds
     * channels while this screen is open, and the 开看 step must count the list the user is about to
     * see, not the one that existed when the screen opened.
     *
     * No `flowOn` here on purpose: all this adds to the repository's flow is two `count`s over an
     * already-materialised list, and the repository owns its own threading (Room queries on its own
     * executor). Keeping the mapping on the collector's context is what makes the state machine's
     * output observable synchronously — the property the JVM tests rely on, and the same reason a
     * stale count would be worse than a cheap one.
     */
    private val catalog: StateFlow<CatalogFacts> = channels
        .observe(ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null))
        .map { list ->
            CatalogFacts(
                channelCount = list.size,
                playableCount = list.count { it.channel.streamCount > 0 },
                loaded = true,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogFacts())

    val uiState: StateFlow<WizardUiState> = combine(
        flow,
        sourceFacts,
        catalog,
        updateState,
    ) { state, sourceInfo, channelInfo, updating ->
        WizardUiState(
            step = state.step,
            stepNumber = state.stepNumber,
            sourceSkipped = state.sourceSkipped,
            updateSkipped = state.updateSkipped,
            builtIns = sourceInfo.builtIns,
            subscriptionCount = sourceInfo.subscriptionCount,
            importedName = sourceInfo.importedName,
            lastImport = sourceInfo.lastImport,
            channelCount = channelInfo.channelCount,
            playableCount = channelInfo.playableCount,
            catalogLoaded = channelInfo.loaded,
            update = updating,
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WizardUiState())

    init {
        // One load for the whole screen: the two reads are a small file and one `SELECT`, both off
        // the main thread behind the ports.
        refreshSources()
        // A run that resumes on 更新 owes the user the same auto-start it would have got by walking
        // there (see [maybeStartUpdate]); resuming on 开看 or 选源 starts nothing.
        maybeStartUpdate(flow.value)
    }

    /** Re-reads the subscriptions and the remembered import (the screen calls this on resume). */
    fun refreshSources() {
        viewModelScope.launch {
            val managed = runCatching { sources.list() }.getOrDefault(emptyList())
            val imported = runCatching { importPort.lastImported() }.getOrNull()
            sourceFacts.update {
                it.copy(subscriptionCount = managed.size, importedName = imported?.name)
            }
        }
    }

    // ---- the step machine (docs/04 P2-9 items 2–3) ----

    /** 继续 on the current step. Entering 更新 starts the run (see [maybeStartUpdate]). */
    fun onContinue() = advance(WizardFlow::next)

    /** 跳过 on the current step: the same advance, with the step recorded as skipped. */
    fun onSkip() = advance(WizardFlow::skip)

    private fun advance(transition: (WizardState) -> WizardState) {
        val next = transition(flow.value)
        flow.value = next
        firstRun.saveProgress(next)
        maybeStartUpdate(next)
    }

    /**
     * BACK. Returns true when the wizard handled it (it moved one step up); false means "this is the
     * first step" and the screen should leave — the same two-level shape the player and the browse
     * screen use (docs/02 §8.1 返回键层级).
     *
     * Leaving the 更新 step while a run is in flight **cancels** it: BACK is the remote's stop
     * gesture, and leaving a foreground job running after the user walked away from it would make the
     * notification the only way back.
     */
    fun onBack(): Boolean {
        val current = flow.value
        if (current.step == WizardStep.UPDATE && updateState.value.isInFlight()) {
            viewModelScope.launch { runCatching { update.cancel() } }
        }
        val previous = WizardFlow.back(current) ?: return false
        flow.value = previous
        firstRun.saveProgress(previous)
        return true
    }

    // ---- 更新 ----

    /** The 开始更新 / 重试 button. */
    fun startUpdate() {
        viewModelScope.launch { runCatching { update.start() } }
    }

    /** The 中断 button. */
    fun cancelUpdate() {
        viewModelScope.launch { runCatching { update.cancel() } }
    }

    /**
     * Auto-start, but only the first time the step is entered. Going BACK from 开看 to 更新 must not
     * re-enqueue a run that already finished (the manual job is `KEEP`, so a re-enqueue would start a
     * fresh one), and a run the user cancelled stays cancelled until they ask for it again.
     */
    private fun maybeStartUpdate(state: WizardState) {
        if (state.step != WizardStep.UPDATE) return
        if (WizardStep.UPDATE in state.skipped) return
        if (updateState.value != WizardUpdateState.NotStarted) return
        startUpdate()
    }

    // ---- 选源 ----

    /**
     * Import a file the user picked from the drop folder (P2-6 item 3). The wizard hosts the same
     * port and the same dialog the browse screen uses — one import implementation, two entrances.
     */
    fun importCandidate(candidate: ImportCandidate) {
        viewModelScope.launch { applyImport(importPort.import(candidate)) }
    }

    /** The SAF half. The screen owns the picker; the port owns the permission and the read. */
    fun importUri(uri: String) {
        viewModelScope.launch { applyImport(importPort.importUri(uri)) }
    }

    private suspend fun applyImport(result: ImportResult) {
        val outcome = when (result) {
            is ImportResult.Done -> ImportOutcome.Done(
                name = result.report.name,
                channels = result.report.channels,
                streams = result.report.streams,
            )

            is ImportResult.Failed -> ImportOutcome.Failed(result.message)
        }
        val imported = runCatching { importPort.lastImported() }.getOrNull()
        sourceFacts.update {
            it.copy(lastImport = outcome, importedName = imported?.name ?: it.importedName)
        }
    }

    // Plain pass-throughs, so the screen does not have to inject the import port a second time.

    /** The drop folder's candidates; a read failure is reported as "nothing to pick". */
    suspend fun candidates(): List<ImportCandidate> =
        runCatching { importPort.candidates() }.getOrDefault(emptyList())

    fun folders(): ImportFolders = importPort.folders()

    // ---- 开看 ----

    /**
     * The 开看 step is done: record the setting the whole card is built around, then let the screen
     * hand over to the channel list. Written here rather than in the screen, so "the wizard was
     * completed" has exactly one author. `markCompleted` also clears the saved step (NEW-1), so a run
     * that finished is never resumed.
     */
    fun complete() {
        firstRun.markCompleted()
    }
}

/** What the wizard screen renders (docs/02 §8.1: immutable data class behind a `StateFlow`). */
data class WizardUiState(
    val step: WizardStep = WizardStep.SOURCE,
    /** 1-based number for "第 N 步 / 共 3 步"; null once the wizard is finished. */
    val stepNumber: Int? = 1,
    val sourceSkipped: Boolean = false,
    val updateSkipped: Boolean = false,
    val builtIns: List<BuiltInSourceInfo> = emptyList(),
    val subscriptionCount: Int = 0,
    val importedName: String? = null,
    val lastImport: WizardViewModel.ImportOutcome? = null,
    val channelCount: Int = 0,
    val playableCount: Int = 0,
    val catalogLoaded: Boolean = false,
    val update: WizardUpdateState = WizardUpdateState.NotStarted,
) {
    /** True when 开看 has nothing to open — the branch the dispatch asks to be explicit about. */
    val hasNoChannels: Boolean get() = catalogLoaded && channelCount == 0
}

/**
 * Is a run in flight? Used by BACK (to cancel) and by the 更新 panel (to enable 中断), so the two
 * cannot disagree about what "running" means.
 */
fun WizardUpdateState.isInFlight(): Boolean =
    this is WizardUpdateState.Preparing || this is WizardUpdateState.Running
