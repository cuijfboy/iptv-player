package ilab.iptv.player.wizard

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.wizard.WizardUpdatePort
import ilab.iptv.player.core.domain.wizard.WizardUpdateResult
import ilab.iptv.player.core.domain.wizard.WizardUpdateRun
import ilab.iptv.player.core.domain.wizard.WizardUpdateSnapshot
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.refresh.RefreshNotifications
import ilab.iptv.player.refresh.RefreshScheduler
import ilab.iptv.player.refresh.RefreshWorkOutcome
import ilab.iptv.player.refresh.RefreshWorkSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The 更新 step's port, implemented on the **existing** manual refresh job (docs/04 P2-9 item 2 ②:
 * 前台服务 + 进度 + 可中断, and "首次更新与手动更新走同一管线（不要另写一套）").
 *
 * WHAT IS REUSED, AND WHAT IT COSTS TO REUSE IT: [RefreshScheduler.enqueueNow] puts
 * [RefreshWorkSpec.MANUAL_NAME] on the queue — the very same unique job the manual trigger already
 * uses, so the wizard gets the P2-5 foreground service, the silent `IMPORTANCE_LOW` notification,
 * the §6.1 budget, the retry policy and breakpoint resume without a second implementation. In
 * exchange this adapter has to *read* WorkManager instead of owning the flow, which is why progress
 * is observed from `WorkInfo` rather than from a callback.
 *
 * THE ONE ADDITION IN THE PIPELINE: `RefreshWorker` now calls `setProgress` with the phase and the
 * counters it already emits for the notification. Nothing else in the worker changed — the retry
 * policy, the result payload and the EPG follow-up are untouched (P2-9 must not change the refresh
 * chain's semantics).
 *
 * `WorkManager` is fetched lazily: `Application.onCreate` (and therefore Hilt's injection of this
 * singleton) can run before `androidx.startup` has finished initialising WorkManager, and a field
 * initialiser would turn that ordering accident into a crash on the cold-start path.
 */
@Singleton
class WorkManagerWizardUpdate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: RefreshScheduler,
    private val logger: Logger,
) : WizardUpdatePort {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun observe(): Flow<WizardUpdateSnapshot> =
        workManager.getWorkInfosForUniqueWorkFlow(RefreshWorkSpec.MANUAL_NAME)
            .map { infos -> infos.lastOrNull()?.let(::snapshotOf) ?: WizardUpdateSnapshot() }

    override suspend fun start() {
        // The same call the manual trigger makes, so the WORK_SCHEDULE line in the log is the same
        // line — "scheduled → ran → what happened" stays one filter.
        scheduler.enqueueNow(RefreshTrigger.MANUAL)
    }

    override suspend fun cancel() {
        workManager.cancelUniqueWork(RefreshWorkSpec.MANUAL_NAME)
        // SERVICE_REFRESH_STOP is the registered code for "the refresh foreground service stopped";
        // `from=wizard` is what makes a user-requested stop distinguishable from a finished run.
        logger.i(
            LogCategory.SERVICE,
            EventCodes.SERVICE_REFRESH_STOP,
            "refresh cancelled from the first-run wizard",
            mapOf(
                "notificationId" to RefreshNotifications.NOTIFICATION_ID,
                "result" to "cancelled",
                "from" to "wizard",
            ),
        )
    }

    /** Flattens one `WorkInfo` into the type the feature module is allowed to see. */
    private fun snapshotOf(info: WorkInfo): WizardUpdateSnapshot {
        val progress = info.progress
        val output = info.outputData
        return WizardUpdateSnapshot(
            run = when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WizardUpdateRun.QUEUED
                WorkInfo.State.RUNNING -> WizardUpdateRun.RUNNING
                WorkInfo.State.SUCCEEDED -> WizardUpdateRun.SUCCEEDED
                WorkInfo.State.FAILED -> WizardUpdateRun.FAILED
                WorkInfo.State.CANCELLED -> WizardUpdateRun.CANCELLED
            },
            // While it runs the phase rides in `progress`; once it is done the same key arrives in
            // `outputData` (RefreshWorkOutcome writes it), so one reader covers both.
            phase = phaseOf(progress.getString(RefreshWorkOutcome.KEY_PHASE))
                ?: phaseOf(output.getString(RefreshWorkOutcome.KEY_PHASE)),
            done = progress.getInt(RefreshWorkOutcome.KEY_DONE, 0),
            total = progress.getInt(RefreshWorkOutcome.KEY_TOTAL, 0),
            result = resultOf(output.getString(RefreshWorkOutcome.KEY_RESULT)),
            okCount = output.getInt(RefreshWorkOutcome.KEY_OK_COUNT, 0),
            failCount = output.getInt(RefreshWorkOutcome.KEY_FAIL_COUNT, 0),
            interrupted = output.getString(RefreshWorkOutcome.KEY_INTERRUPTED) != null,
            detail = output.getString(RefreshWorkOutcome.KEY_ERROR)
                ?: output.getString(RefreshWorkOutcome.KEY_INTERRUPTED),
        )
    }

    private fun phaseOf(name: String?): RefreshPhase? =
        name?.let { value -> RefreshPhase.entries.firstOrNull { it.name == value } }

    /**
     * The worker answers `success` for all three of its terminal payloads on purpose (a `failure`
     * on a periodic job can stop the daily schedule — P2-5), so the wizard reads the payload. An
     * unknown or missing value stays [WizardUpdateResult.UNKNOWN] and is reported as a failure
     * rather than optimistically as success.
     */
    private fun resultOf(name: String?): WizardUpdateResult? = when (name) {
        RefreshWorkOutcome.RESULT_COMPLETED -> WizardUpdateResult.COMPLETED
        RefreshWorkOutcome.RESULT_DEFERRED -> WizardUpdateResult.DEFERRED
        RefreshWorkOutcome.RESULT_GAVE_UP -> WizardUpdateResult.GAVE_UP
        null -> null
        else -> WizardUpdateResult.UNKNOWN
    }
}
