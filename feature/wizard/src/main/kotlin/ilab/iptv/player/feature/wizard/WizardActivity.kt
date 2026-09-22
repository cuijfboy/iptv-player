package ilab.iptv.player.feature.wizard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.wizard.WizardFlow
import ilab.iptv.player.core.domain.wizard.WizardStep
import ilab.iptv.player.core.domain.wizard.WizardUpdateFailure
import ilab.iptv.player.core.domain.wizard.WizardUpdateState
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.ui.browse.BrowseContract
import ilab.iptv.player.core.ui.import.ImportCandidateLabel
import ilab.iptv.player.core.ui.import.ImportEntrance
import ilab.iptv.player.core.ui.import.ImportPickContent
import ilab.iptv.player.core.ui.import.ImportPickerDialog
import ilab.iptv.player.core.ui.import.ImportPickerModel
import ilab.iptv.player.core.ui.settings.SettingsContract
import ilab.iptv.player.core.ui.wizard.WizardContract
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The P2-9 first-run wizard (docs/04 P2-9, docs/02 §8.1's `WizardActivity（仅首次）`).
 *
 * Three steps in one activity — ① 选源 → ② 更新 → ③ 开看 — because they are one decision with one
 * start and one end: three activities would put three entries on the back stack for a flow the user
 * should never have to navigate by anything but 继续 / 跳过 / 返回.
 *
 * WHAT THIS CLASS OWNS, AND WHAT IT DOES NOT:
 * - it renders [WizardUiState] and routes clicks to [WizardViewModel] (docs/02 §8.1's one-way data
 *   flow: the view never calls a repository);
 * - it owns the two things that need an Android `Context` — starting the other screens and the
 *   system file picker. Everything decidable without a device is decided in the view model or in
 *   `:core:domain`, which is why the step machine, the skip branches and the no-source branch are
 *   JVM tests instead of a click-through.
 *
 * **It is the only screen the wizard adds.** 导入本地清单 uses the shared `ImportPickerDialog` and
 * the same `PlaylistImportPort` the browse screen uses (P2-6); 添加订阅 opens the existing
 * source-management page through `SettingsContract`; 开看 hands over to `BrowseActivity` with a hint
 * instead of re-implementing the channel list.
 *
 * BACK (docs/02 §8.1 返回键层级): inside the wizard BACK walks one step up, and from the first step
 * it leaves. Leaving writes no completion flag, so an abandoned wizard comes back on the next launch
 * rather than dropping the user into a half-configured app.
 */
@AndroidEntryPoint
class WizardActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    private val viewModel: WizardViewModel by viewModels()

    private lateinit var header: TextView
    private lateinit var hint: TextView
    private lateinit var sourcePanel: LinearLayout
    private lateinit var updatePanel: LinearLayout
    private lateinit var watchPanel: LinearLayout
    private lateinit var sourceSummary: TextView
    private lateinit var sourceList: TextView
    private lateinit var sourceStatus: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateProgress: ProgressBar
    private lateinit var updateStart: Button
    private lateinit var updateCancel: Button
    private lateinit var watchStatus: TextView
    private lateinit var watchOpen: Button

    /** The step the last log line was written for, so one screen-open event is one step entry. */
    private var loggedStep: WizardStep? = null

    /** The step currently inflated, so focus is requested when a step *changes* and not every tick. */
    private var renderedStep: WizardStep? = null

    /** The SAF half of 导入本地清单: the same `OpenDocument` contract the browse screen uses. */
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, R.string.wizard_import_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        viewModel.importUri(uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        header = findViewById(R.id.wizard_header)
        hint = findViewById(R.id.wizard_hint)
        sourcePanel = findViewById(R.id.wizard_step_source)
        updatePanel = findViewById(R.id.wizard_step_update)
        watchPanel = findViewById(R.id.wizard_step_watch)
        sourceSummary = findViewById(R.id.wizard_source_summary)
        sourceList = findViewById(R.id.wizard_source_list)
        sourceStatus = findViewById(R.id.wizard_source_status)
        updateStatus = findViewById(R.id.wizard_update_status)
        updateProgress = findViewById(R.id.wizard_update_progress)
        updateStart = findViewById(R.id.wizard_update_start)
        updateCancel = findViewById(R.id.wizard_update_cancel)
        watchStatus = findViewById(R.id.wizard_watch_status)
        watchOpen = findViewById(R.id.wizard_watch_open)

        findViewById<Button>(R.id.wizard_source_continue).setOnClickListener { viewModel.onContinue() }
        findViewById<Button>(R.id.wizard_source_import).setOnClickListener { openImportPicker() }
        findViewById<Button>(R.id.wizard_source_subscribe).setOnClickListener {
            // The existing source-management page (P2-6). Coming back re-reads the subscription
            // count, so 选源 shows what the user just added instead of what it showed on entry.
            startActivity(SettingsContract.sourceManagementIntent(this))
        }
        findViewById<Button>(R.id.wizard_source_skip).setOnClickListener { viewModel.onSkip() }

        updateStart.setOnClickListener { viewModel.startUpdate() }
        updateCancel.setOnClickListener { viewModel.cancelUpdate() }
        findViewById<Button>(R.id.wizard_update_skip).setOnClickListener { viewModel.onSkip() }

        watchOpen.setOnClickListener { enterChannelList(play = true) }
        findViewById<Button>(R.id.wizard_watch_import).setOnClickListener { openImportPicker() }
        findViewById<Button>(R.id.wizard_watch_skip).setOnClickListener { enterChannelList(play = false) }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.onBack()) return
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            },
        )

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                logStepEntry(state)
                render(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 添加订阅 / 导入 can change the counts while this screen is paused.
        viewModel.refreshSources()
    }

    override fun onStop() {
        super.onStop()
        // docs/03 §5: an exit or a power cut must not cost the last events.
        logger.flush()
    }

    /**
     * The wizard's observability (docs/04 P2-9 item 4). docs/03 §3.3 has no `WIZARD_*` code and this
     * card must not invent one, so each step entry is the registered `UI_SCREEN_OPEN` with
     * `screen=Wizard` and the step in `step` — the same code the main / browse / settings screens
     * already emit, which keeps "which screen opened" a single filter in the troubleshooting manual.
     */
    private fun logStepEntry(state: WizardUiState) {
        if (state.step == loggedStep) return
        loggedStep = state.step
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "wizard step shown",
            fields = mapOf(
                "screen" to SCREEN_NAME,
                "step" to state.step.name,
                "number" to state.stepNumber,
                "sourceSkipped" to state.sourceSkipped,
                "updateSkipped" to state.updateSkipped,
                "reason" to WizardContract.readReason(intent),
            ),
        )
    }

    private fun render(state: WizardUiState) {
        val number = state.stepNumber
        header.text = if (number == null) {
            getString(R.string.wizard_step_watch)
        } else {
            getString(
                R.string.wizard_header,
                number,
                WizardFlow.TOTAL_STEPS,
                getString(stepTitle(state.step)),
            )
        }
        hint.text = getString(
            if (state.step == WizardStep.SOURCE) {
                R.string.wizard_hint_back_exit
            } else {
                R.string.wizard_hint_back_previous
            },
        )

        val changed = state.step != renderedStep
        renderedStep = state.step
        sourcePanel.visibility = visibleIf(state.step == WizardStep.SOURCE)
        updatePanel.visibility = visibleIf(state.step == WizardStep.UPDATE)
        watchPanel.visibility = visibleIf(state.step == WizardStep.WATCH)

        when (state.step) {
            WizardStep.SOURCE -> renderSource(state)
            WizardStep.UPDATE -> renderUpdate(state)
            WizardStep.WATCH -> renderWatch(state)
            WizardStep.FINISHED -> Unit
        }

        // Never leave a step focus-less: a step whose first button does not take focus is a step the
        // remote cannot drive (docs/02 §8.2).
        if (changed || currentFocus == null) {
            firstButtonOf(state.step)?.requestFocus()
        }
    }

    // ---- step 1 · 选源 ----

    private fun renderSource(state: WizardUiState) {
        sourceSummary.text = getString(R.string.wizard_source_summary, state.builtIns.size)
        sourceList.text = if (state.builtIns.isEmpty()) {
            getString(R.string.wizard_source_list_placeholder)
        } else {
            state.builtIns.joinToString("\n") { source -> "· ${source.label}" }
        }

        val lines = ArrayList<String>(4)
        lines += getString(R.string.wizard_source_subscriptions, state.subscriptionCount)
        lines += getString(
            R.string.wizard_source_imported,
            state.importedName ?: getString(R.string.wizard_source_import_none),
        )
        when (val imported = state.lastImport) {
            is WizardViewModel.ImportOutcome.Done ->
                lines += getString(
                    R.string.wizard_import_done,
                    imported.name,
                    imported.channels,
                    imported.streams,
                )

            is WizardViewModel.ImportOutcome.Failed -> lines += imported.message
            null -> Unit
        }
        if (state.sourceSkipped) lines += getString(R.string.wizard_source_skipped)
        sourceStatus.text = lines.joinToString("\n")
    }

    // ---- step 2 · 更新 ----

    private fun renderUpdate(state: WizardUiState) {
        val update = state.update
        val inFlight = update.isInFlight()
        updateStatus.text = updateText(state)
        updateProgress.visibility = visibleIf(inFlight)
        when (update) {
            is WizardUpdateState.Running -> {
                // A local, because `percent` lives in another module and Kotlin cannot smart-cast a
                // property across a module boundary even when it is a `val`.
                val percent = update.percent
                updateProgress.isIndeterminate = percent == null
                if (percent != null) updateProgress.progress = percent
            }

            WizardUpdateState.Preparing -> updateProgress.isIndeterminate = true
            else -> Unit
        }
        updateStart.visibility = visibleIf(!inFlight)
        updateStart.text = getString(
            if (update is WizardUpdateState.NotStarted) {
                R.string.wizard_update_start
            } else {
                R.string.wizard_update_retry
            },
        )
        updateCancel.visibility = visibleIf(inFlight)
    }

    private fun updateText(state: WizardUiState): String {
        val text = when (val update = state.update) {
            WizardUpdateState.NotStarted -> getString(R.string.wizard_update_idle)
            WizardUpdateState.Preparing -> getString(R.string.wizard_update_preparing)
            is WizardUpdateState.Running -> {
                val phase = phaseLabel(update.phase)
                if (update.percent == null) {
                    getString(R.string.wizard_update_running, phase)
                } else {
                    getString(R.string.wizard_update_running_percent, phase, update.percent)
                }
            }

            is WizardUpdateState.Done -> getString(
                if (update.partial) R.string.wizard_update_done_partial else R.string.wizard_update_done,
                update.okCount,
                update.failCount,
            )

            is WizardUpdateState.Failed -> getString(
                when (update.reason) {
                    WizardUpdateFailure.RUN_FAILED -> R.string.wizard_update_failed_run
                    WizardUpdateFailure.GAVE_UP -> R.string.wizard_update_failed_gave_up
                    WizardUpdateFailure.DEFERRED -> R.string.wizard_update_failed_deferred
                    WizardUpdateFailure.UNKNOWN -> R.string.wizard_update_failed_unknown
                },
                update.detail ?: getString(R.string.wizard_phase_unknown),
            )

            WizardUpdateState.Cancelled -> getString(R.string.wizard_update_cancelled)
        }
        return if (state.updateSkipped) {
            text + "\n" + getString(R.string.wizard_update_skipped)
        } else {
            text
        }
    }

    /** Chinese stage labels for the progress line (docs/02 §6.1's stage names). */
    private fun phaseLabel(phase: RefreshPhase?): String = getString(
        when (phase) {
            RefreshPhase.FETCH -> R.string.wizard_phase_fetch
            RefreshPhase.PARSE -> R.string.wizard_phase_parse
            RefreshPhase.NORMALIZE -> R.string.wizard_phase_normalize
            RefreshPhase.DEDUPE -> R.string.wizard_phase_dedupe
            RefreshPhase.SHALLOW -> R.string.wizard_phase_shallow
            RefreshPhase.DEEP -> R.string.wizard_phase_deep
            RefreshPhase.SCORE -> R.string.wizard_phase_score
            RefreshPhase.SELECT -> R.string.wizard_phase_select
            RefreshPhase.PERSIST -> R.string.wizard_phase_persist
            RefreshPhase.DONE -> R.string.wizard_phase_done
            null -> R.string.wizard_phase_unknown
        },
    )

    // ---- step 3 · 开看 ----

    private fun renderWatch(state: WizardUiState) {
        watchStatus.text = when {
            !state.catalogLoaded -> getString(R.string.wizard_watch_loading)
            state.hasNoChannels -> getString(R.string.wizard_watch_empty)
            else -> getString(R.string.wizard_watch_ready, state.channelCount, state.playableCount)
        }
        // With nothing to watch, "进入频道表（按 OK 播放）" would be an empty promise, so it is
        // disabled and 导入本地清单 (this panel's first enabled button) takes the focus instead.
        watchOpen.isEnabled = !state.hasNoChannels
    }

    /**
     * The 开看 hand-over: record completion, then start the channel list with (or without) the
     * "focus the first playable channel and say 按 OK 播放" hint. `CLEAR_TASK or NEW_TASK` is docs/02
     * §8.1's 向导退栈 contract — BACK from the list must not return to the wizard.
     */
    private fun enterChannelList(play: Boolean) {
        viewModel.complete()
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "wizard handed over to the channel list",
            fields = mapOf(
                "screen" to SCREEN_NAME,
                "step" to WizardStep.WATCH.name,
                "hint" to if (play) BrowseContract.HINT_FIRST_PLAY else "none",
            ),
        )
        val destination = BrowseContract.intent(
            this,
            if (play) BrowseContract.Hint.FIRST_PLAY else null,
        )
        destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(destination)
        finish()
    }

    // ---- the shared local-import dialog (P2-6) ----

    private fun openImportPicker() {
        lifecycleScope.launch {
            val current = viewModel.uiState.value.importedName ?: getString(R.string.wizard_import_none)
            val folders = viewModel.folders()
            ImportPickerDialog.showList(
                context = this@WizardActivity,
                title = getString(R.string.wizard_import_title),
                hint = getString(R.string.wizard_import_hint, current, folders.dropFolder),
                rows = ImportEntrance.entries.map(::entranceLabel),
                cancelLabel = getString(R.string.wizard_import_cancel),
            ) { which ->
                when (ImportEntrance.entries[which]) {
                    ImportEntrance.SystemPicker -> openDocumentPicker()
                    ImportEntrance.DropFolder -> showDropPicker()
                }
            }
        }
    }

    private fun entranceLabel(entrance: ImportEntrance): String = getString(
        when (entrance) {
            ImportEntrance.SystemPicker -> R.string.wizard_import_pick_saf
            ImportEntrance.DropFolder -> R.string.wizard_import_pick_drop
        },
    )

    private fun openDocumentPicker() {
        try {
            documentPicker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.wizard_import_saf_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDropPicker() {
        lifecycleScope.launch {
            val candidates = viewModel.candidates()
            when (
                val content = ImportPickerModel.pickContent(
                    candidates = candidates,
                    emptyMessage = getString(
                        R.string.wizard_import_empty,
                        viewModel.folders().dropFolder,
                    ),
                    rowLabel = { ImportCandidateLabel.describe(it) },
                )
            ) {
                is ImportPickContent.Empty -> ImportPickerDialog.showMessage(
                    context = this@WizardActivity,
                    title = getString(R.string.wizard_import_title),
                    message = content.message,
                    closeLabel = getString(R.string.wizard_import_close),
                )

                is ImportPickContent.Files -> ImportPickerDialog.showList(
                    context = this@WizardActivity,
                    title = getString(R.string.wizard_import_title),
                    rows = content.rows,
                    cancelLabel = getString(R.string.wizard_import_cancel),
                ) { which -> viewModel.importCandidate(candidates[which]) }
            }
        }
    }

    private fun stepTitle(step: WizardStep): Int = when (step) {
        WizardStep.SOURCE -> R.string.wizard_step_source
        WizardStep.UPDATE -> R.string.wizard_step_update
        WizardStep.WATCH, WizardStep.FINISHED -> R.string.wizard_step_watch
    }

    /** The button the remote should land on when a step opens: the step's primary action. */
    private fun firstButtonOf(step: WizardStep): View? = when (step) {
        WizardStep.SOURCE -> findViewById(R.id.wizard_source_continue)
        WizardStep.UPDATE -> if (updateStart.visibility == View.VISIBLE) updateStart else updateCancel
        WizardStep.WATCH ->
            if (watchOpen.isEnabled) watchOpen else findViewById(R.id.wizard_watch_import)

        WizardStep.FINISHED -> null
    }

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private companion object {
        const val SCREEN_NAME = "Wizard"
    }
}
