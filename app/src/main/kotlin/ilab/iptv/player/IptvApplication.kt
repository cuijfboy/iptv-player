package ilab.iptv.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.log.DeviceInfo
import ilab.iptv.player.refresh.RefreshScheduler
import javax.inject.Inject

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
}
