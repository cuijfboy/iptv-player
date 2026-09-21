package ilab.iptv.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.log.DeviceInfo
import javax.inject.Inject

/**
 * Hilt root (docs/02 §3.1). All extension points (sources, scorers, player engines, log sinks)
 * are bound here through `@IntoSet` multibindings.
 */
@HiltAndroidApp
class IptvApplication : Application() {

    @Inject
    lateinit var logger: Logger

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
        // Never drop the tail on the way out: the ring is what the device page shows.
        logger.flush()
    }
}
