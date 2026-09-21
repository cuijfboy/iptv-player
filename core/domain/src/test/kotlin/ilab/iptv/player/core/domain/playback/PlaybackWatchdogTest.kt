package ilab.iptv.player.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/*
 * The three phenomena of docs/02 §6.2 (起播超时 / 长时间无进度 / 卡顿) plus their false-positive
 * boundary: a short freeze, a pause, or a buffering window that does make progress must stay `Ok`,
 * because those are exactly the moments where a wrong verdict costs the user a channel switch.
 */
class PlaybackWatchdogTest {

    private val config = WatchdogConfig(
        prepareTimeoutMs = 12_000,
        progressThresholdMs = 8_000,
        bufferingThresholdMs = 8_000,
    )

    private fun watchdog(nowMs: Long = 0L): Pair<PlaybackWatchdog, MutableClock> {
        val clock = MutableClock(nowMs)
        return PlaybackWatchdog(clock, config) to clock
    }

    private fun sample(
        positionMs: Long,
        bufferedPositionMs: Long = positionMs,
        isLoading: Boolean = false,
        nowMs: Long,
        isPaused: Boolean = false,
    ) = PlaybackSample(
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        isLoading = isLoading,
        nowMs = nowMs,
        isPaused = isPaused,
    )

    // ---- 起播超时 ------------------------------------------------------------------------------

    @Test
    fun `prepare timeout fires at the threshold and not before`() {
        val (watchdog, clock) = watchdog()
        watchdog.onPrepareStart()

        clock.nowMs = 11_999
        assertThat(watchdog.poll()).isEqualTo(WatchdogVerdict.Ok)

        clock.nowMs = 12_000
        val verdict = watchdog.poll()
        assertThat(verdict).isInstanceOf(WatchdogVerdict.PrepareTimeout::class.java)
        verdict as WatchdogVerdict.PrepareTimeout
        assertThat(verdict.waitedMs).isEqualTo(12_000)
        assertThat(verdict.timeoutMs).isEqualTo(12_000)
    }

    @Test
    fun `a first frame takes the start-up timeout off the table`() {
        val (watchdog, clock) = watchdog()
        watchdog.onPrepareStart()
        watchdog.onFirstFrame(nowMs = 3_000)

        clock.nowMs = 600_000
        assertThat(watchdog.poll()).isEqualTo(WatchdogVerdict.Ok)
    }

    @Test
    fun `samples during preparation do not clear the start-up timeout`() {
        val (watchdog, clock) = watchdog()
        watchdog.onPrepareStart(nowMs = 0)

        assertThat(watchdog.onSample(sample(positionMs = 0, bufferedPositionMs = 5_000, isLoading = true, nowMs = 1_000)))
            .isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 0, bufferedPositionMs = 5_000, isLoading = true, nowMs = 6_000)))
            .isEqualTo(WatchdogVerdict.Ok)

        clock.nowMs = 12_000
        assertThat(watchdog.poll()).isInstanceOf(WatchdogVerdict.PrepareTimeout::class.java)
    }

    @Test
    fun `the prepare clock restarts with the next stream`() {
        val (watchdog, clock) = watchdog()
        watchdog.onPrepareStart(nowMs = 0)
        clock.nowMs = 20_000
        assertThat(watchdog.poll()).isInstanceOf(WatchdogVerdict.PrepareTimeout::class.java)

        watchdog.onPrepareStart(nowMs = 20_000)
        clock.nowMs = 25_000
        assertThat(watchdog.poll()).isEqualTo(WatchdogVerdict.Ok)
    }

    // ---- 长时间无进度 --------------------------------------------------------------------------

    @Test
    fun `a frozen position is not a stall until the threshold`() {
        val (watchdog, _) = watchdog()
        watchdog.onPrepareStart(nowMs = 0)
        watchdog.onFirstFrame(nowMs = 0)

        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 0))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 7_999))).isEqualTo(WatchdogVerdict.Ok)

        val verdict = watchdog.onSample(sample(positionMs = 0, bufferedPositionMs = 4_000, nowMs = 8_000))
        assertThat(verdict).isInstanceOf(WatchdogVerdict.NoProgress::class.java)
        verdict as WatchdogVerdict.NoProgress
        assertThat(verdict.signal.stalledMs).isEqualTo(8_000)
        assertThat(verdict.signal.positionMs).isEqualTo(0)
        assertThat(verdict.signal.bufferedPositionMs).isEqualTo(4_000)
        assertThat(verdict.thresholdMs).isEqualTo(8_000)
    }

    @Test
    fun `progress resets the anchor so a short jitter never switches`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, nowMs = 0))

        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 7_500))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 1_200, nowMs = 8_000))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 1_200, nowMs = 15_000))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 1_200, nowMs = 16_000)))
            .isInstanceOf(WatchdogVerdict.NoProgress::class.java)
    }

    @Test
    fun `a paused stream never stalls`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, nowMs = 0))

        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 60_000, isPaused = true)))
            .isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 600_000, isPaused = true)))
            .isEqualTo(WatchdogVerdict.Ok)
    }

    @Test
    fun `a repeated verdict stays a verdict until the controller acts`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, nowMs = 0))

        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 8_000)))
            .isInstanceOf(WatchdogVerdict.NoProgress::class.java)
        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 9_000)))
            .isInstanceOf(WatchdogVerdict.NoProgress::class.java)
    }

    // ---- 卡顿 ---------------------------------------------------------------------------------

    @Test
    fun `a buffering window that does not advance becomes a stall`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, nowMs = 0))

        assertThat(watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 1_000)))
            .isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 8_999)))
            .isEqualTo(WatchdogVerdict.Ok)

        val verdict = watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 9_000))
        assertThat(verdict).isInstanceOf(WatchdogVerdict.Buffering::class.java)
        assertThat((verdict as WatchdogVerdict.Buffering).signal.stalledMs).isEqualTo(8_000)
    }

    @Test
    fun `a buffering window restarts while the position still moves`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, nowMs = 0))
        watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 0))

        assertThat(watchdog.onSample(sample(positionMs = 1_000, isLoading = true, nowMs = 5_000)))
            .isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 1_000, isLoading = true, nowMs = 12_000)))
            .isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 1_000, isLoading = true, nowMs = 13_000)))
            .isInstanceOf(WatchdogVerdict.Buffering::class.java)
    }

    @Test
    fun `leaving buffering returns to progress accounting`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 0))
        assertThat(watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 9_000)))
            .isInstanceOf(WatchdogVerdict.Buffering::class.java)

        assertThat(watchdog.onSample(sample(positionMs = 2_000, nowMs = 9_500))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.phase).isEqualTo(WatchdogPhase.PLAYING)
    }

    // ---- boundaries & plumbing ----------------------------------------------------------------

    @Test
    fun `an idle or stopped watchdog never reports`() {
        val (watchdog, _) = watchdog()
        assertThat(watchdog.phase).isEqualTo(WatchdogPhase.IDLE)
        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 600_000))).isEqualTo(WatchdogVerdict.Ok)

        watchdog.onStopped()
        assertThat(watchdog.phase).isEqualTo(WatchdogPhase.STOPPED)
        assertThat(watchdog.poll(nowMs = 900_000)).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.nextDeadlineMs()).isNull()
    }

    @Test
    fun `poll re-judges the last sample without a fresh one`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 500, nowMs = 0))
        assertThat(watchdog.poll(nowMs = 1_000)).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.poll(nowMs = 8_000)).isInstanceOf(WatchdogVerdict.NoProgress::class.java)
    }

    @Test
    fun `polling before the first sample reports nothing`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        assertThat(watchdog.poll(nowMs = 60_000)).isEqualTo(WatchdogVerdict.Ok)
    }

    @Test
    fun `the next deadline follows the phase`() {
        val (watchdog, _) = watchdog()
        assertThat(watchdog.nextDeadlineMs()).isNull()

        watchdog.onPrepareStart(nowMs = 1_000)
        assertThat(watchdog.nextDeadlineMs()).isEqualTo(13_000)

        watchdog.onFirstFrame(nowMs = 2_000)
        assertThat(watchdog.nextDeadlineMs()).isEqualTo(10_000)

        watchdog.onSample(sample(positionMs = 0, isLoading = true, nowMs = 3_000))
        assertThat(watchdog.nextDeadlineMs()).isEqualTo(11_000)
    }

    @Test
    fun `the injected clock supplies the default timestamps`() {
        val (watchdog, clock) = watchdog(nowMs = 5_000)
        watchdog.onPrepareStart()
        assertThat(watchdog.nextDeadlineMs()).isEqualTo(17_000)

        clock.nowMs = 17_000
        assertThat(watchdog.poll()).isInstanceOf(WatchdogVerdict.PrepareTimeout::class.java)
    }

    @Test
    fun `a new stream resets the progress anchor`() {
        val (watchdog, _) = watchdog()
        watchdog.onFirstFrame(nowMs = 0)
        watchdog.onSample(sample(positionMs = 0, nowMs = 0))
        assertThat(watchdog.onSample(sample(positionMs = 0, nowMs = 9_000)))
            .isInstanceOf(WatchdogVerdict.NoProgress::class.java)

        watchdog.onPrepareStart(nowMs = 9_000)
        assertThat(watchdog.phase).isEqualTo(WatchdogPhase.PREPARING)
        watchdog.onFirstFrame(nowMs = 10_000)
        assertThat(watchdog.onSample(sample(positionMs = 40_000, nowMs = 10_000))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 40_000, nowMs = 17_000))).isEqualTo(WatchdogVerdict.Ok)
        assertThat(watchdog.onSample(sample(positionMs = 40_000, nowMs = 18_000)))
            .isInstanceOf(WatchdogVerdict.NoProgress::class.java)
    }
}
