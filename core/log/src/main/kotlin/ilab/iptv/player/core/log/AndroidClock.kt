package ilab.iptv.player.core.log

import android.os.SystemClock
import ilab.iptv.player.core.common.Clock

/**
 * Real envelope time sources (docs/03 §4). Wall clock for `ts`, process uptime for `elapsedMs`
 * (uptime is immune to NTP jumps, which is what stall detection needs).
 */
class AndroidClock : Clock, UptimeMs {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun readMs(): Long = SystemClock.elapsedRealtime()
}
