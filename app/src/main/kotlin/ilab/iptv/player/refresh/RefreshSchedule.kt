package ilab.iptv.player.refresh

import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import java.util.Calendar
import java.util.TimeZone

/**
 * The wall-clock half of "默认每日 06:00 触发一次刷新" (docs/04 P2-5).
 *
 * WHY IT IS PURE: the only interesting part is arithmetic — "how long until the next local 06:00?"
 * — and it is the part that silently breaks (off-by-one at exactly 06:00, a day dropped when the
 * device's clock is already past the target, a DST day). WorkManager itself is a thin mapping on top
 * (see [RefreshWorkSpec]); the numbers below are unit-tested on the JVM with a fixed time zone.
 *
 * The period stays a real 24 h (`PeriodicWorkRequest` has no calendar concept), so on a DST
 * transition day the wall-clock anchor moves by the offset delta; the *delay is recomputed on every
 * app start* (see `RefreshScheduler.scheduleDaily`), which pulls it back.
 */
object RefreshSchedule {

    /** One day, the periodic interval. */
    const val PERIOD_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * Flex lets the platform pick the moment inside `[target, target + flex]` (WorkManager's floor is
     * 5 min). Five minutes of slack is enough for the OS to batch the job with something else and
     * small enough that "06:00" is still the honest description.
     */
    const val FLEX_MS: Long = 5L * 60L * 1000L

    /**
     * Milliseconds from [nowMs] to the next occurrence of [minuteOfDay] local time.
     *
     * `nowMs` exactly on the target means *tomorrow*: today's run already had its moment, and
     * returning 0 here would make every app start at 06:00:00.000 enqueue an immediate run.
     */
    fun nextDelayMs(
        nowMs: Long,
        minuteOfDay: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val minute = RefreshScheduleSettings.sanitize(minuteOfDay)
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= nowMs) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis - nowMs
    }
}
