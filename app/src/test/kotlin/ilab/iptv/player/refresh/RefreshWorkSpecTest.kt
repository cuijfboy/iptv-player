package ilab.iptv.player.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.refresh.PlaybackAvoidancePolicy
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/** The constraint/retry decisions of docs/04 P2-5 item 1, asserted as data (no WorkManager needed). */
class RefreshWorkSpecTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private val now: Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 22, 5, 0)
    }.timeInMillis

    @Test
    fun `the daily job carries the interval, the first delay and the constraints`() {
        val spec = RefreshWorkSpec.periodic(minuteOfDay = 6 * 60, nowMs = now, zone = zone)

        assertThat(spec.uniqueName).isEqualTo(RefreshWorkSpec.PERIODIC_NAME)
        assertThat(spec.periodMs).isEqualTo(24L * 60 * 60_000L)
        assertThat(spec.flexMs).isEqualTo(RefreshSchedule.FLEX_MS)
        assertThat(spec.initialDelayMs).isEqualTo(60L * 60_000L)
        assertThat(spec.requiresNetwork).isTrue()
        assertThat(spec.requiresBatteryNotLow).isTrue()
    }

    @Test
    fun `the retry policy is linear 30 min, at most three attempts`() {
        val spec = RefreshWorkSpec.periodic(6 * 60, now, zone)

        assertThat(spec.linearBackoff).isTrue()
        assertThat(spec.backoffMs).isEqualTo(30L * 60_000L)
        assertThat(spec.backoffMs).isEqualTo(PlaybackAvoidancePolicy.DEFER_BACKOFF_MS)
        assertThat(spec.maxAttempts).isEqualTo(3)
    }

    @Test
    fun `the immediate job runs now and still retries`() {
        val spec = RefreshWorkSpec.immediate()

        assertThat(spec.uniqueName).isEqualTo(RefreshWorkSpec.MANUAL_NAME)
        assertThat(spec.initialDelayMs).isEqualTo(0L)
        assertThat(spec.requiresNetwork).isTrue()
        // A manual run is the user waiting; a low battery must not block it (the constraints still
        // require a network, because a refresh without one can only fail).
        assertThat(spec.requiresBatteryNotLow).isFalse()
        assertThat(spec.backoffMs).isEqualTo(PlaybackAvoidancePolicy.DEFER_BACKOFF_MS)
        assertThat(spec.maxAttempts).isEqualTo(3)
    }
}
