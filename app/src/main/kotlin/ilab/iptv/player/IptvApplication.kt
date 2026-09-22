package ilab.iptv.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ilab.iptv.player.core.common.AppScopeProvider
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.core.log.DeviceInfo
import ilab.iptv.player.epg.EpgRefreshScheduler
import ilab.iptv.player.refresh.RefreshScheduler
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Hilt root (docs/02 §3.1). All extension points (sources, scorers, player engines, log sinks)
 * are bound here through `@IntoSet` multibindings.
 */
@HiltAndroidApp
class IptvApplication : Application() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var refreshScheduler: RefreshScheduler

    @Inject
    lateinit var epgRefreshScheduler: EpgRefreshScheduler

    @Inject
    lateinit var appScope: AppScopeProvider

    override fun onCreate() {
        super.onCreate()
        // `APP_START` (docs/03 §3.3, INFO) — the envelope is stamped by the real Clock/UptimeMs/
        // SessionIdFactory binding, so this line is also the live proof that the log pipeline runs.
        logger.i(
            category = LogCategory.APP,
            code = EventCodes.APP_START,
            message = "app started",
            fields = mapOf(
                "version" to DeviceInfo.appVersion(this),
                "device" to DeviceInfo.deviceSummary(),
                "maxMemoryMb" to DeviceInfo.maxMemoryMb(),
            ),
        )
        scheduleRefresh()
        scheduleEpg()
        // Never drop the tail on the way out: the ring is what the device page shows.
        logger.flush()
    }

    /**
     * P2-5: the daily refresh is queued on every start (`ExistingPeriodicWorkPolicy.UPDATE`), which is
     * what keeps the 06:00 anchor correct after a setting change or a long uptime — the first-run delay
     * is recomputed from the clock, never carried over from a previous process.
     *
     * A failure here must not take the app down: WorkManager itself can be unavailable (the platform
     * refusing to schedule, storage full for its database). The failure is logged and the next start
     * tries again, because the alternative — no app at all — is strictly worse than a missed refresh.
     */
    private fun scheduleRefresh() {
        try {
            refreshScheduler.scheduleDaily()
        } catch (e: Throwable) {
            logger.w(
                category = LogCategory.WORK,
                code = EventCodes.WORK_SCHEDULE,
                message = "daily refresh could not be scheduled",
                fields = mapOf("defaultMinuteOfDay" to 6 * 60),
                error = e,
            )
        }
    }

    /**
     * P3-6: every start asks for one EPG refresh, off the main thread.
     *
     * WHY IT IS ASYNC AND NOT A BLOCKING CALL: this is `Application.onCreate`, on the cold-start path
     * the S6 budget is measured against. The trigger's decision needs one small read (the
     * `epg_source` status), so the request is launched on the injected app scope and the start
     * continues immediately; when the decision is "fresh", nothing is even queued.
     *
     * WHAT IT IS NOT: it is not a download on the startup path. The gate only decides whether to queue
     * a WorkManager job, and the job itself runs on its own thread with network and battery
     * constraints (`EpgRefreshScheduler`).
     */
    private fun scheduleEpg() {
        appScope.appScope.launch {
            try {
                epgRefreshScheduler.request(RefreshTrigger.FIRST_RUN)
            } catch (e: Throwable) {
                // Same rule as the daily schedule: a failure here must not take the app down, and the
                // next start tries again.
                logger.w(
                    category = LogCategory.WORK,
                    code = EventCodes.WORK_SCHEDULE,
                    message = "epg refresh could not be requested",
                    fields = mapOf("job" to "epg", "trigger" to RefreshTrigger.FIRST_RUN.name),
                    error = e,
                )
            }
        }
    }
}
