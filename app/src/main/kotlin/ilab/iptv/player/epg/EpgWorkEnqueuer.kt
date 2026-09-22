package ilab.iptv.player.epg

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The one thing P3-6 needs from WorkManager: put an EPG job on the queue.
 *
 * It is an interface for the same reason [ilab.iptv.player.refresh.WorkEnqueuer] is — `WorkManager`
 * cannot be constructed in a JVM test, so [EpgRefreshScheduler] and [EpgRefreshCoordinator] are tested
 * against a recording fake and this adapter is the mechanical part left for the device.
 */
interface EpgWorkEnqueuer {

    fun enqueue(spec: EpgRefreshWorkSpec)
}

/** The production adapter: one `OneTimeWorkRequest` per spec. */
class WorkManagerEpgEnqueuer(private val workManager: WorkManager) : EpgWorkEnqueuer {

    override fun enqueue(spec: EpgRefreshWorkSpec) {
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setConstraints(constraintsOf(spec))
            .setInitialDelay(spec.initialDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(backoffOf(spec), spec.backoffMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EpgRefreshWorker.KEY_TRIGGER to spec.trigger.name))
            .addTag(EpgRefreshWorkSpec.TAG)
            .build()
        workManager.enqueueUniqueWork(
            spec.uniqueName,
            if (spec.replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun backoffOf(spec: EpgRefreshWorkSpec): BackoffPolicy =
        if (spec.linearBackoff) BackoffPolicy.LINEAR else BackoffPolicy.EXPONENTIAL

    /** The pure spec → the Android constraints object. Kept public so a test can read it back. */
    fun constraintsOf(spec: EpgRefreshWorkSpec): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (spec.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
        )
        .setRequiresBatteryNotLow(spec.requiresBatteryNotLow)
        .build()

    companion object {

        /** `WorkManager` boots through `androidx.startup`, so this works from `Application.onCreate`. */
        fun from(context: Context): WorkManagerEpgEnqueuer =
            WorkManagerEpgEnqueuer(WorkManager.getInstance(context.applicationContext))
    }
}
