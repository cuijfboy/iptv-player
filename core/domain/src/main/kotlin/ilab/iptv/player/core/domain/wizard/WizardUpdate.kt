package ilab.iptv.player.core.domain.wizard

import ilab.iptv.player.core.model.RefreshPhase
import kotlinx.coroutines.flow.Flow

/**
 * The wizard's "更新" step (docs/04 P2-9 item 2: 前台服务 + 进度 + 可中断；失败给可读提示与重试).
 *
 * **There is no second pipeline here.** The step drives the *same* manual refresh job the settings
 * page and the browse page drive (`RefreshScheduler.enqueueNow(MANUAL)` → `RefreshWorker` →
 * `RefreshRunCoordinator` → `RefreshSourcesUseCase`), so it gets the same foreground service, the
 * same notification, the same budget and the same breakpoint-resume behaviour for free. What this
 * port adds is only the *view*: the run's progress, its outcome and a cancel, in types a feature
 * module is allowed to see.
 *
 * WHY THE SNAPSHOT IS A FLAT DATA CLASS AND NOT AN ANDROID TYPE: `WorkInfo` cannot be constructed in
 * a JVM test, so the `:app` adapter flattens what it read into [WizardUpdateSnapshot] and the whole
 * snapshot → screen-state decision ([WizardUpdateReading]) becomes testable without a device.
 */

/** The lifecycle of one update run, as far as the wizard needs to distinguish it. */
enum class WizardUpdateRun {
    /** Nothing has been asked for yet in this session and no run is on record. */
    NONE,

    /** Queued, waiting for the network/battery constraints. */
    QUEUED,

    RUNNING,
    SUCCEEDED,

    /** The job itself failed (not the same as "the run finished and reported a failure"). */
    FAILED,

    /** Cancelled — by the wizard's 中断 button, or by the system dropping the job. */
    CANCELLED,
}

/**
 * How a *successful* job reported itself. `RefreshWorkOutcome` answers WorkManager with `success` for
 * all three of these on purpose (a failed periodic worker can take the daily schedule with it), so
 * "the job succeeded" and "the refresh worked" are not the same thing and the wizard must read the
 * payload instead of the state.
 */
enum class WizardUpdateResult { COMPLETED, DEFERRED, GAVE_UP, UNKNOWN }

/**
 * Why a queued run has not started yet (NEW-004, docs/05-过程记录/67).
 *
 * The P2-9 screen showed one sentence — 「已排队，等待网络…」 — for every queued run. That was wrong
 * for the run WorkManager rescheduled after a process death: the network was fine, the job was simply
 * waiting out the backoff of the attempt that was killed. One enum splits the two so the screen can
 * say the true thing instead of guessing "network".
 */
enum class WizardUpdateWait {

    /** Queued normally: waiting for the job's constraints (network, battery). */
    AWAITING_CONSTRAINTS,

    /** Queued again after the previous run was interrupted; it will start as soon as it is allowed. */
    RETRY_AFTER_INTERRUPTION,
}

/** One reading of the manual refresh job, with the Android types already stripped off. */
data class WizardUpdateSnapshot(
    val run: WizardUpdateRun = WizardUpdateRun.NONE,
    /** The phase the pipeline last reported, null before the first emission. */
    val phase: RefreshPhase? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** The worker's `result` payload; only meaningful when [run] is [WizardUpdateRun.SUCCEEDED]. */
    val result: WizardUpdateResult? = null,
    val okCount: Int = 0,
    val failCount: Int = 0,
    /** The run stopped early on the §6.1 budget; what it wrote stays, so this is not a failure. */
    val interrupted: Boolean = false,
    /** The failure's short class name, for the readable message ("IOException"). */
    val detail: String? = null,
    /** Only meaningful when [run] is [WizardUpdateRun.QUEUED]: why it has not started. */
    val wait: WizardUpdateWait = WizardUpdateWait.AWAITING_CONSTRAINTS,
)

/** Why the update step did not produce a usable library — the wizard shows one sentence per value. */
enum class WizardUpdateFailure {
    /** The pipeline threw (`run=FAILED`). */
    RUN_FAILED,

    /** The job gave up after its retries (`result=gaveUp`). */
    GAVE_UP,

    /** The run was deferred because a playback session was active. */
    DEFERRED,

    /** A terminal payload the wizard does not recognise — reported honestly instead of as success. */
    UNKNOWN,
}

/** What the 更新 step renders. */
sealed interface WizardUpdateState {

    /** Nothing started: the step shows its invitation and the 开始更新 button. */
    data object NotStarted : WizardUpdateState

    /** Queued or started, before the pipeline reported its first phase. [wait] says why it is queued. */
    data class Preparing(val wait: WizardUpdateWait) : WizardUpdateState

    /** [percent] is null when the phase has no item counter — an indeterminate bar. */
    data class Running(val phase: RefreshPhase?, val percent: Int?) : WizardUpdateState

    /** The run reached `DONE`. [partial] is the budget-interrupted case. */
    data class Done(val okCount: Int, val failCount: Int, val partial: Boolean) : WizardUpdateState

    data class Failed(val reason: WizardUpdateFailure, val detail: String?) : WizardUpdateState

    /** Interrupted on purpose. Not an error: the rows the run wrote are kept. */
    data object Cancelled : WizardUpdateState
}

/**
 * snapshot → screen state, as a pure function.
 *
 * The percentage rule is the one thing here that breaks by arithmetic, and it is the same rule the
 * P2-5 notification uses: `total = 0` means "this phase has no denominator yet", not "0 %", and a
 * `DONE` frame is 100 % even when its counters are zero.
 */
object WizardUpdateReading {

    fun of(snapshot: WizardUpdateSnapshot): WizardUpdateState = when (snapshot.run) {
        WizardUpdateRun.NONE -> WizardUpdateState.NotStarted
        WizardUpdateRun.QUEUED -> WizardUpdateState.Preparing(snapshot.wait)
        WizardUpdateRun.RUNNING -> WizardUpdateState.Running(snapshot.phase, percentOf(snapshot))
        WizardUpdateRun.FAILED -> WizardUpdateState.Failed(
            WizardUpdateFailure.RUN_FAILED,
            snapshot.detail,
        )

        WizardUpdateRun.CANCELLED -> WizardUpdateState.Cancelled
        WizardUpdateRun.SUCCEEDED -> succeeded(snapshot)
    }

    /** `done / total` as a whole percentage, or null when the phase has no denominator. */
    fun percentOf(snapshot: WizardUpdateSnapshot): Int? {
        if (snapshot.phase == null) return null
        if (snapshot.phase == RefreshPhase.DONE) return 100
        if (snapshot.total <= 0) return null
        return (snapshot.done * 100 / snapshot.total).coerceIn(0, 100)
    }

    /**
     * Does *entering* the 更新 step start a round? (卡 WIZARD-BACK-1)
     *
     * [WizardUpdateState.NotStarted] cannot answer this on its own: it is also what the screen holds
     * before the port has been read at all, and that is exactly the moment a screen that resumes on
     * 更新 asks (the wizard remembers its step, NEW-1). A caller that trusts it enqueues a second full
     * run for a round that is already on the queue or already finished — on the device that was two
     * `SRC_REFRESH_DONE`s of ≈152 s each, the second produced by walking BACK onto this step (G7-1
     * §6②). So the caller reads the port first and asks this instead:
     *
     * - [WizardUpdateRun.NONE] — nothing on record: the "新用户 3 步内看到画面" auto-start.
     * - [WizardUpdateRun.QUEUED] with [WizardUpdateWait.RETRY_AFTER_INTERRUPTION] — the previous
     *   attempt was killed and sits in WorkManager's backoff: re-asking is how that run is reclaimed
     *   (NEW-004), and it cannot double-run because the queue is not empty.
     * - everything else — a round that already exists (running, queued normally, or finished as
     *   success, failure or cancellation): entering the step again must not put another one on the
     *   queue. A finished-and-failed or cancelled round is re-started only by the user's own button.
     */
    fun startsOnEntry(snapshot: WizardUpdateSnapshot): Boolean = when (snapshot.run) {
        WizardUpdateRun.NONE -> true
        WizardUpdateRun.QUEUED -> snapshot.wait == WizardUpdateWait.RETRY_AFTER_INTERRUPTION

        WizardUpdateRun.RUNNING,
        WizardUpdateRun.SUCCEEDED,
        WizardUpdateRun.FAILED,
        WizardUpdateRun.CANCELLED,
        -> false
    }

    private fun succeeded(snapshot: WizardUpdateSnapshot): WizardUpdateState = when (snapshot.result) {
        // The job says success because a failed *periodic* worker would take the daily schedule with
        // it (P2-5). The wizard still has to call it a failure: nothing arrived.
        WizardUpdateResult.GAVE_UP -> WizardUpdateState.Failed(WizardUpdateFailure.GAVE_UP, snapshot.detail)
        WizardUpdateResult.DEFERRED ->
            WizardUpdateState.Failed(WizardUpdateFailure.DEFERRED, snapshot.detail)

        WizardUpdateResult.COMPLETED -> WizardUpdateState.Done(
            okCount = snapshot.okCount,
            failCount = snapshot.failCount,
            partial = snapshot.interrupted,
        )

        // A payload the adapter could not decode: reporting "done" would promise channels that may
        // not be there.
        WizardUpdateResult.UNKNOWN, null ->
            WizardUpdateState.Failed(WizardUpdateFailure.UNKNOWN, snapshot.detail)
    }
}

/**
 * The port the 更新 step is written against. Implemented in `:app` (WorkManager + the P2-5
 * scheduler), consumed in `:feature:wizard`, so no feature module ever sees WorkManager.
 */
interface WizardUpdatePort {

    /** The manual refresh job's current reading; emits `NONE` when the job has never run. */
    fun observe(): Flow<WizardUpdateSnapshot>

    /** Enqueue the manual refresh — the same unique job the manual trigger elsewhere enqueues. */
    suspend fun start()

    /**
     * Stop the run. The pipeline writes every verdict as it produces it and skips rows whose health
     * is still fresh (P2-5 breakpoint resume), so cancelling costs the run's remaining work, never
     * the rows it already wrote.
     */
    suspend fun cancel()
}
