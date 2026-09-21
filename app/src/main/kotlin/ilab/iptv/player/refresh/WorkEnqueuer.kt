package ilab.iptv.player.refresh

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ilab.iptv.player.core.model.RefreshTrigger
import java.util.concurrent.TimeUnit

/**
 * The one thing P2-5 needs from WorkManager: put a job on the queue (docs/04 P2-5).
 *
 * It is an interface for one reason — `WorkManager` cannot be constructed in a JVM unit test without
 * Robolectric, so [RefreshScheduler] (the part with the decisions and the logs) is tested against a
 * recording fake, and [WorkManagerEnqueuer] is the mechanical adapter left for the device ("等价假体",
 * docs/04 P2-5 item 6).
 */
interface WorkEnqueuer {

    /** Enqueue/replace the daily job. `UPDATE` so a changed setting takes effect on the next app start. */
    fun enqueuePeriodic(spec: RefreshWorkSpec)

    /** Enqueue the immediate job; `KEEP` so two taps do not queue two runs. */
    fun enqueueOnce(spec: RefreshWorkSpec)
}

/** The production adapter: builds the two WorkRequests and hands them to WorkManager. */
class WorkManagerEnqueuer(private val workManager: WorkManager) : WorkEnqueuer {

    override fun enqueuePeriodic(spec: RefreshWorkSpec) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            spec.periodMs,
            TimeUnit.MILLISECONDS,
            spec.flexMs,
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(constraintsOf(spec))
            .setInitialDelay(spec.initialDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(backoffOf(spec), spec.backoffMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RefreshWorker.KEY_TRIGGER to RefreshTrigger.SCHEDULED.name))
            .addTag(RefreshScheduler.TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            spec.uniqueName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun enqueueOnce(spec: RefreshWorkSpec) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraintsOf(spec))
            .setInitialDelay(spec.initialDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(backoffOf(spec), spec.backoffMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RefreshWorker.KEY_TRIGGER to RefreshTrigger.MANUAL.name))
            .addTag(RefreshScheduler.TAG)
            .build()
        workManager.enqueueUniqueWork(
            spec.uniqueName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun backoffOf(spec: RefreshWorkSpec): BackoffPolicy =
        if (spec.linearBackoff) BackoffPolicy.LINEAR else BackoffPolicy.EXPONENTIAL

    /** The pure spec → the Android constraints object. Kept public so a test can read it back. */
    fun constraintsOf(spec: RefreshWorkSpec): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (spec.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
        )
        .setRequiresBatteryNotLow(spec.requiresBatteryNotLow)
        .build()

    companion object {

        /** `WorkManager` boots through `androidx.startup`, so this works from `Application.onCreate`. */
        fun from(context: Context): WorkManagerEnqueuer =
            WorkManagerEnqueuer(WorkManager.getInstance(context.applicationContext))
    }
}
