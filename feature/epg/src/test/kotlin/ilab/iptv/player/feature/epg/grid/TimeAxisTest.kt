package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/**
 * The daylight-saving cases the work order names explicitly ("时间↔像素映射（含时区/夏令时边界）").
 *
 * Europe/Berlin is used because its transitions are the classic ones: 2026-03-29 02:00 → 03:00 (a 23-hour
 * civil day) and 2026-10-25 03:00 → 02:00 (a 25-hour one).
 */
class TimeAxisTest {

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")
    private val axis = TimeAxis(berlin, stepMinutes = 30)

    private fun berlin(y: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(berlin).apply {
            clear()
            set(y, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun dayEnd(startMs: Long): Long =
        Calendar.getInstance(berlin).apply {
            timeInMillis = startMs
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis - 1

    private fun ticksOfDay(y: Int, month: Int, day: Int): List<TimeTick> {
        val start = berlin(y, month, day, 0)
        return axis.ticks(start, dayEnd(start))
    }

    @Test
    fun `an ordinary day has 48 half-hour ticks`() {
        val ticks = ticksOfDay(2026, 6, 15)
        assertThat(ticks).hasSize(48)
        assertThat(ticks.first().label).isEqualTo("00:00")
        assertThat(ticks.last().label).isEqualTo("23:30")
    }

    @Test
    fun `the spring-forward day skips the hour that does not exist`() {
        val ticks = ticksOfDay(2026, 3, 29)
        assertThat(ticks).hasSize(46)
        val labels = ticks.map { it.label }
        assertThat(labels).contains("01:30")
        assertThat(labels).contains("03:00")
        assertThat(labels).doesNotContain("02:00")
        assertThat(labels).doesNotContain("02:30")
    }

    @Test
    fun `the fall-back day repeats the hour that happens twice`() {
        val ticks = ticksOfDay(2026, 10, 25)
        assertThat(ticks).hasSize(50)
        assertThat(ticks.count { it.label == "02:00" }).isEqualTo(2)
        assertThat(ticks.count { it.label == "02:30" }).isEqualTo(2)
    }

    @Test
    fun `tick instants increase by exactly one step even across a transition`() {
        for ((month, day) in listOf(3 to 29, 10 to 25)) {
            val ticks = ticksOfDay(2026, month, day)
            val deltas = ticks.zipWithNext { previous, next -> next.atMs - previous.atMs }
            // The grid is linear in real time: the *labels* jump or repeat, the instants do not.
            assertThat(deltas.all { it == 30 * MINUTE_MS }).isTrue()
        }
    }

    @Test
    fun `stepping across the spring transition costs half an hour of real time`() {
        val before = berlin(2026, 3, 29, 1, 30)
        val after = axis.addSteps(before, 1)
        assertThat(after - before).isEqualTo(30 * MINUTE_MS)
        assertThat(axis.label(after)).isEqualTo("03:00")
    }

    @Test
    fun `floor and ceil align to the step grid`() {
        val axis = TimeAxis(TimeZone.getTimeZone("Asia/Shanghai"), stepMinutes = 30)
        val base = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(2026, 5, 15, 10, 47, 30)
        }.timeInMillis
        assertThat(axis.label(axis.floorToStep(base))).isEqualTo("10:30")
        assertThat(axis.label(axis.ceilToStep(base))).isEqualTo("11:00")
        // An instant already on a tick is its own floor and ceil.
        val onTick = axis.floorToStep(base)
        assertThat(axis.floorToStep(onTick)).isEqualTo(onTick)
        assertThat(axis.ceilToStep(onTick)).isEqualTo(onTick)
    }

    @Test
    fun `day boundaries are midnight ticks and are marked`() {
        val ticks = ticksOfDay(2026, 6, 15)
        val dayBoundaries = ticks.filter { it.isDayBoundary }
        assertThat(dayBoundaries).hasSize(1)
        assertThat(dayBoundaries.first().label).isEqualTo("00:00")
        assertThat(axis.dayLabel(dayBoundaries.first().atMs)).isEqualTo("06-15")
    }

    @Test
    fun `a nonsense window answers no ticks instead of looping`() {
        assertThat(axis.ticks(1_000L, 500L)).isEmpty()
        assertThat(axis.ticks(0L, 0L)).hasSize(1)
    }
}
