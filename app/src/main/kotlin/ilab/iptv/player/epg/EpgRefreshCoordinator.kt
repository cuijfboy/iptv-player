package ilab.iptv.player.epg

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.epg.EpgSourceStatusReader
import ilab.iptv.player.core.data.epg.EpgStoredGuideReader
import ilab.iptv.player.core.data.epg.LoadEpgUseCase
import ilab.iptv.player.core.domain.refresh.EpgRefreshAction
import ilab.iptv.player.core.domain.refresh.EpgRefreshBudget
import ilab.iptv.player.core.domain.refresh.EpgRefreshDecision
import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.model.EpgLoadReport
import ilab.iptv.player.core.model.RefreshTrigger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** What one EPG run is asked to do. [respectPlayback] is R7's switch for the run-time half. */
data class EpgRunOptions(val trigger: RefreshTrigger, val respectPlayback: Boolean)

/**
 * The EPG pipeline as a port, so [EpgRefreshCoordinator] can be unit-tested on the JVM: the real
 * [LoadEpgUseCase] is `final` and needs the whole Hilt graph, while a fake only has to answer "what
 * did you return / did you throw". Same trade as `RefreshRunner` in the P2-5 coordinator.
 */
fun interface EpgRunner {

    suspend fun run(options: EpgRunOptions): AppResult<EpgLoadReport>
}

/** The production adapter: the one EPG use case, and the only thing that ever calls it. */
class UseCaseEpgRunner(private val useCase: LoadEpgUseCase) : EpgRunner {

    override suspend fun run(options: EpgRunOptions): AppResult<EpgLoadReport> =
        useCase(respectPlayback = options.respectPlayback)
}

/** What one EPG run did, in the terms the worker needs to answer WorkManager with. */
sealed interface EpgRunResult {

    data class Completed(val report: EpgLoadReport) : EpgRunResult

    /** The trigger found nothing to do (EPG off, or the stored guide still inside its min interval). */
    data class Skipped(val reason: String) : EpgRunResult

    /** Playback was on and the policy said to wait, so no network work happened. */
    data class Deferred(val trigger: RefreshTrigger, val deferrals: Int) : EpgRunResult

    /** The pipeline threw or the budget ran out; [reason] is a stable token for the log. */
    data class Failed(val reason: String) : EpgRunResult
}

/**
 * One EPG run, with the P3-6 trigger decision in front of it.
 *
 * This is the single funnel the dispatch asks for: the cold start ([RefreshTrigger.FIRST_RUN]), the
 * follow-up after a source refresh ([RefreshTrigger.SCHEDULED]) and the panel's button
 * ([RefreshTrigger.MANUAL]) all arrive here, all consult the same pure [EpgRefreshPolicy], and all run
 * the same use case under the same [EpgRefreshBudget]. There is no second EPG path in `:app`.
 *
 * THE THREE ANSWERS, IN ORDER:
 * 1. *before* starting: [EpgRefreshPolicy.decide] — off/fresh → [EpgRunResult.Skipped] (nothing is
 *    read, nothing is written: this is what makes repeated triggers idempotent), playing → `Deferred`
 *    while [EpgRefreshPolicy.MAX_DEFERRALS] allows it, and no channel list yet →
 *    `Deferred(catalog_empty)` with no attempt cap of its own, because waiting for the catalogue is
 *    cheap (no network) and giving up on it is what left a fresh install without a guide
 *    (BUG-20260922-016);
 * 2. *during* the run: the use case re-reads the same playback signal between sources, so a session
 *    that starts mid-run stops the remaining guides (see `LoadEpgUseCase`'s class doc);
 * 3. *around* the run: a wall-clock budget, so a stuck socket becomes a reported failure the retry
 *    policy can act on instead of a job that never returns.
 */
class EpgRefreshCoordinator(
    private val runner: EpgRunner,
    private val policy: EpgRefreshPolicy,
    private val settings: EpgRefreshSettings,
    private val status: EpgSourceStatusReader,
    private val guide: EpgStoredGuideReader,
    private val playback: PlaybackProbe,
    private val clock: Clock,
    private val logger: Logger,
) {

    /** Is a session using the engine right now? ([PlaybackActivity] in production, a fake in tests.) */
    fun interface PlaybackProbe {

        fun isActive(): Boolean
    }

    suspend fun run(
        trigger: RefreshTrigger,
        deferrals: Int = 0,
        respectPlayback: Boolean = true,
        budgetMs: Long = EpgRefreshBudget.DEFAULT_BUDGET_MS,
    ): EpgRunResult {
        val playing = playback.isActive()
        val stored = guide.read()
        val decision = policy.decide(
            trigger = trigger,
            playing = playing,
            respectPlayback = respectPlayback,
            deferrals = deferrals,
            lastFetchAtMs = status.read().lastFetchAtMs,
            nowMs = clock.nowMs(),
            settings = settings,
            stored = stored,
        )
        when (decision.action) {
            EpgRefreshAction.SKIP -> {
                log(EventCodes.WORK_RUN, "epg refresh skipped", decision, trigger, playing, deferrals)
                return EpgRunResult.Skipped(decision.reason)
            }

            EpgRefreshAction.DEFER -> {
                log(
                    EventCodes.WORK_RUN,
                    // Two ways to defer, one answer to WorkManager: the TV is busy, or there is no
                    // channel list to bind yet (BUG-20260922-016). The reason field says which.
                    if (decision.reason == EpgRefreshPolicy.REASON_PLAYING) {
                        "epg refresh deferred while playing"
                    } else {
                        "epg refresh deferred until the channel list exists"
                    },
                    decision,
                    trigger,
                    playing,
                    deferrals,
                )
                return EpgRunResult.Deferred(trigger = trigger, deferrals = deferrals)
            }

            EpgRefreshAction.RUN -> Unit
        }

        val startedAtMs = clock.nowMs()
        return try {
            val result = withTimeout(budgetMs) {
                runner.run(EpgRunOptions(trigger = trigger, respectPlayback = respectPlayback))
            }
            when (result) {
                is AppResult.Ok -> {
                    val report = result.value
                    logger.i(
                        LogCategory.WORK,
                        EventCodes.WORK_RUN,
                        "epg refresh finished",
                        fields(decision, trigger, playing, deferrals) + mapOf(
                            "result" to if (report.interrupted == null) "success" else "interrupted",
                            "providers" to report.providers,
                            "programmes" to report.programmes,
                            "skippedRows" to report.skipped,
                            "coverageMatched" to report.coverage.matched,
                            // EPG-BIND: the id-side reading and the one that says a viewer sees
                            // something, so the run's log answers both without the coverage event.
                            "coverageWithProgrammes" to report.coverage.withProgrammes,
                            "coverageEmptyBinding" to report.coverage.emptyBinding,
                            "coverageTotal" to report.coverage.total,
                            "interrupted" to report.interrupted,
                            "elapsedMs" to (clock.nowMs() - startedAtMs),
                        ),
                    )
                    EpgRunResult.Completed(report)
                }

                is AppResult.Err -> {
                    val reason = "error:${result.error.failure.name}"
                    logFailure(decision, trigger, playing, deferrals, startedAtMs, reason)
                    EpgRunResult.Failed(reason)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // The budget, not the caller: report it as a failure so the retry policy owns what happens
            // next. Rows already written stay (docs/02 §6.3).
            logFailure(decision, trigger, playing, deferrals, startedAtMs, REASON_BUDGET)
            EpgRunResult.Failed(REASON_BUDGET)
        } catch (e: CancellationException) {
            // docs/02 §4.5 C5 / F2: cancellation is a stop signal, not a failure. WorkManager cancels
            // doWork() when the constraints drop or the job is replaced, and swallowing it would leave
            // the run pretending to have finished.
            throw e
        } catch (e: Throwable) {
            val reason = e::class.simpleName ?: "unknown"
            logFailure(decision, trigger, playing, deferrals, startedAtMs, reason, e)
            EpgRunResult.Failed(reason)
        }
    }

    private suspend fun logFailure(
        decision: EpgRefreshDecision,
        trigger: RefreshTrigger,
        playing: Boolean,
        deferrals: Int,
        startedAtMs: Long,
        reason: String,
        error: Throwable? = null,
    ) {
        logger.w(
            LogCategory.WORK,
            EventCodes.WORK_RUN,
            "epg refresh failed",
            fields(decision, trigger, playing, deferrals) + mapOf(
                "result" to "failed",
                "reason" to reason,
                "elapsedMs" to (clock.nowMs() - startedAtMs),
            ),
            error,
        )
    }

    private fun log(
        code: String,
        message: String,
        decision: EpgRefreshDecision,
        trigger: RefreshTrigger,
        playing: Boolean,
        deferrals: Int,
    ) {
        logger.i(LogCategory.WORK, code, message, fields(decision, trigger, playing, deferrals))
    }

    /** Every EPG event carries `job=epg`, so one grep separates it from the P2-5 refresh events. */
    private fun fields(
        decision: EpgRefreshDecision,
        trigger: RefreshTrigger,
        playing: Boolean,
        deferrals: Int,
    ): Map<String, Any?> = mapOf(
        "job" to JOB,
        "trigger" to trigger.name,
        "decision" to decision.action.name,
        "reason" to decision.reason,
        "playing" to playing,
        "deferrals" to deferrals,
    )

    companion object {

        const val JOB: String = "epg"

        const val REASON_BUDGET: String = "budget_exceeded"
    }
}
