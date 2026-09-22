package ilab.iptv.player.feature.epg.grid

import ilab.iptv.player.core.model.EpgGridWindow
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One ruler tick. [isDayBoundary] is the midnight label the grid renders wider. */
data class TimeTick(
    val atMs: Long,
    val label: String,
    val isHourBoundary: Boolean,
    val isDayBoundary: Boolean,
)

/**
 * The time ruler (docs/02 §8.3 `时间轴吸顶`) and the step grid the remote moves along.
 *
 * **Why `Calendar` and not `java.time`.** The project's `minSdk` is 21 and there is no core-library
 * desugaring (`docs/05-过程记录` 19 records the same call for the import dialog), so `java.time` is not
 * on the runtime classpath. `Calendar` is what the rest of the app already uses (`XmltvTime`,
 * `RefreshSchedule`).
 *
 * **Daylight saving.** Tick instants are produced by `Calendar.add(MINUTE, …)`, which advances *real*
 * time and then re-reads the wall clock. On a spring-forward day the 02:00 and 02:30 ticks therefore
 * do not exist (the labels jump 01:30 → 03:00) and on a fall-back day the 02:00–02:59 labels appear
 * twice. Instants stay strictly increasing and the count per civil day is 48 / 46 / 50 — the three
 * cases `TimeAxisTest` pins.
 */
class TimeAxis(
    val zone: TimeZone = TimeZone.getDefault(),
    val stepMinutes: Int = 30,
) {
    init {
        require(stepMinutes > 0) { "stepMinutes must be positive, was $stepMinutes" }
    }

    /** `HH:mm` in [zone], 24-hour. Built from fields so no `SimpleDateFormat` sharing is needed. */
    fun label(atMs: Long): String {
        val calendar = calendarAt(atMs)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return "${two(hour)}:${two(minute)}"
    }

    /** `MM-dd` in [zone] — the ruler's day-boundary label. */
    fun dayLabel(atMs: Long): String {
        val calendar = calendarAt(atMs)
        return "${two(calendar.get(Calendar.MONTH) + 1)}-${two(calendar.get(Calendar.DAY_OF_MONTH))}"
    }

    /**
     * Largest step instant ≤ [atMs]. Delegates to [EpgGridWindow.floorToStep] so the ruler and the
     * coverage window cannot align differently (BUG-20260922-018): one implementation, two callers.
     */
    fun floorToStep(atMs: Long): Long = EpgGridWindow.floorToStep(atMs, stepMinutes, zone)

    /** Smallest step instant ≥ [atMs]. */
    fun ceilToStep(atMs: Long): Long {
        val floored = floorToStep(atMs)
        return if (floored == atMs) floored else addSteps(floored, 1)
    }

    /**
     * [atMs] moved by [steps] ruler steps. `Calendar`'s time-field arithmetic is absolute (it adds the
     * milliseconds, then re-reads the fields), which is exactly the DST behaviour described above.
     */
    fun addSteps(atMs: Long, steps: Int): Long {
        val calendar = calendarAt(atMs)
        calendar.add(Calendar.MINUTE, steps * stepMinutes)
        return calendar.timeInMillis
    }

    /** Aligns an instant down to its tick — what a fresh grid opens on. */
    fun alignWindowStart(atMs: Long): Long = floorToStep(atMs)

    /**
     * Ticks covering `[fromMs, toMs]`. Bounded by [MAX_TICKS] so a caller that passes a nonsense window
     * (or a zone with a bizarre transition) can never hang the UI thread.
     */
    fun ticks(fromMs: Long, toMs: Long): List<TimeTick> {
        if (toMs < fromMs) return emptyList()
        val ticks = ArrayList<TimeTick>()
        var at = ceilToStep(fromMs)
        var guard = 0
        while (at <= toMs && guard < MAX_TICKS) {
            val calendar = calendarAt(at)
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            ticks += TimeTick(
                atMs = at,
                label = label(at),
                isHourBoundary = minute == 0,
                isDayBoundary = hour == 0 && minute == 0,
            )
            at = addSteps(at, 1)
            guard++
        }
        return ticks
    }

    private fun calendarAt(atMs: Long): Calendar =
        Calendar.getInstance(zone, Locale.US).apply { timeInMillis = atMs }

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()

    private companion object {
        /** 24 h at 30-minute steps is 48; the cap is 80× that, purely a hang guard. */
        const val MAX_TICKS = 4096
    }
}
