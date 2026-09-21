package ilab.iptv.player.refresh

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.refresh.PlaybackAvoidancePolicy
import ilab.iptv.player.core.domain.refresh.RefreshRunDecision
import ilab.iptv.player.core.model.RefreshInterruption
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshProgress
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.core.data.refresh.RefreshSourcesUseCase
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The refresh pipeline as a port (docs/02 §4.3 `RefreshSourcesUseCase`), so the coordinator below can
 * be unit-tested on the JVM: the real use case is `final` and needs the whole Hilt graph, while a
 * fake only has to answer "which progress did you emit / did you throw".
 */
fun interface RefreshRunner {
    fun run(options: RefreshOptions): Flow<RefreshProgress>
}

/** The production adapter: `:core:data`'s P2-4 pipeline. */
class UseCaseRefreshRunner(private val useCase: RefreshSourcesUseCase) : RefreshRunner {
    override fun run(options: RefreshOptions): Flow<RefreshProgress> = useCase(options)
}

/** What one refresh run did, in the terms the worker needs to answer WorkManager with. */
sealed interface RefreshRunResult {

    /** The pipeline reached `DONE`. [interrupted] is set when it stopped early on the §6.1 budget. */
    data class Completed(val last: RefreshProgress, val interrupted: RefreshInterruption?) : RefreshRunResult

    /** Playback was on and the policy said to wait (R7), so no budget was spent. */
    data class Deferred(val trigger: RefreshTrigger, val deferrals: Int) : RefreshRunResult

    /** The pipeline threw. The worker decides whether that is worth another attempt. */
    data class Failed(val error: Throwable) : RefreshRunResult
}

/**
 * One refresh run, with the R7 playback-avoidance decision in front of it (docs/04 P2-5 items 3 and
 * 4, docs/02 §4.5 C3).
 *
 * THE TWO HALVES OF R7, IN ORDER:
 * 1. *Before* starting: [PlaybackAvoidancePolicy] — a `SCHEDULED` run defers while a session is being
 *    prepared/buffered/played/failing over (at most `MAX_DEFERRALS` times, see the policy);
 * 2. *During* the run: the pipeline halves Fetch/Shallow/Deep concurrency by itself
 *    (`ConcurrencyGovernor`), which is why `respectPlayback` is always passed as `true` here.
 *
 * BREAKPOINT RESUME (docs/04 P2-5 item 4): nothing here deletes or resets state. The pipeline writes
 * every verdict as it is produced and skips streams whose health is still fresh (24 h healthy / 6 h
 * failed TTL in Room), so a run killed mid-flight resumes where it stopped and a deferred/retried run
 * never repeats a completed probe. The coordinator only counts and reports.
 */
class RefreshRunCoordinator(
    private val runner: RefreshRunner,
    private val logger: Logger,
    private val policy: PlaybackAvoidancePolicy,
    private val playback: PlaybackProbe,
    private val clock: Clock,
) {

    /** Is a session using the engine right now? ([PlaybackActivity] in production, a fake in tests.) */
    fun interface PlaybackProbe {
        fun isActive(): Boolean
    }

    suspend fun run(
        trigger: RefreshTrigger,
        deferrals: Int = 0,
        respectPlayback: Boolean = true,
        onProgress: suspend (RefreshProgress) -> Unit = {},
    ): RefreshRunResult {
        val playing = playback.isActive()
        val decision = policy.decide(
            playing = playing,
            respectPlayback = respectPlayback,
            trigger = trigger,
            deferrals = deferrals,
        )
        if (decision == RefreshRunDecision.DEFER) {
            logger.i(
                LogCategory.SERVICE,
                EventCodes.SERVICE_REFRESH_STOP,
                "refresh deferred while playing",
                mapOf("trigger" to trigger.name, "deferrals" to deferrals, "playing" to true),
            )
            return RefreshRunResult.Deferred(trigger = trigger, deferrals = deferrals)
        }

        val startedAtMs = clock.nowMs()
        var last: RefreshProgress? = null
        return try {
            runner.run(RefreshOptions(trigger = trigger, respectPlayback = respectPlayback))
                .collect { progress ->
                    last = progress
                    onProgress(progress)
                }
            val final = last
            if (final == null) {
                RefreshRunResult.Failed(IllegalStateException("refresh pipeline emitted no progress"))
            } else {
                RefreshRunResult.Completed(last = final, interrupted = final.interrupted)
            }
        } catch (e: CancellationException) {
            // docs/02 §4.5 C5 / F2: cancellation is not a failure, it is a stop signal. WorkManager
            // cancels doWork() when the constraints drop or the job is replaced, and swallowing this
            // would leave the run pretending to have finished.
            throw e
        } catch (e: Throwable) {
            logger.w(
                LogCategory.SOURCE,
                EventCodes.SRC_REFRESH_DONE,
                "refresh run failed",
                mapOf(
                    "trigger" to trigger.name,
                    "elapsedMs" to (clock.nowMs() - startedAtMs),
                    "lastPhase" to last?.phase?.name,
                ),
                error = e,
            )
            RefreshRunResult.Failed(e)
        }
    }

}
