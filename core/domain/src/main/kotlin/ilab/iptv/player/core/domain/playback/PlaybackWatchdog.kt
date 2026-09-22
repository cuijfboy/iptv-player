package ilab.iptv.player.core.domain.playback

import ilab.iptv.player.core.common.Clock

/*
 * The watchdog's three thresholds (docs/02 §6.2 "看门狗").
 *
 * `prepareTimeoutMs` mirrors `PlaybackRequest.timeoutMs` (docs/02 §4.2, default 12 s) — the
 * controller builds the watchdog from the same constant so "起播超时" means the same thing to both.
 * SWITCH-P95-1 makes that mirroring per-prepare: the controller may start one attempt with a shorter
 * window (the "fast first attempt" of a channel that has a backup) and passes the same number here
 * through [PlaybackWatchdog.onPrepareStart], so the engine's timeout and the watchdog's deadline can
 * never disagree. [prepareTimeoutMs] stays the default for every attempt that does not say otherwise.
 * `progressThresholdMs` mirrors `FailoverLimits.stallThresholdMs` (default 8 s): the watchdog needs
 * its own copy because it runs before a `FailoverInput` exists, and the controller is expected to
 * wire both from one number.
 */
data class WatchdogConfig(
    val prepareTimeoutMs: Long = 12_000,
    val progressThresholdMs: Long = 8_000,
    val bufferingThresholdMs: Long = 8_000,
)

/** Where the watch currently is. `PREPARING` = `prepare()` sent, first frame not seen yet. */
enum class WatchdogPhase { IDLE, PREPARING, PLAYING, BUFFERING, STOPPED }

/*
 * One playback observation, pushed by the controller out of the engine's state (§7.3/§7.4):
 * position, buffered position, and whether the engine is buffering right now.
 *
 * `isPaused` is expected from the user-visible state (`PlaybackCommand.Pause`): a paused stream has
 * a frozen position by design, so it can never be a stall.
 */
data class PlaybackSample(
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val isLoading: Boolean,
    val nowMs: Long,
    val isPaused: Boolean = false,
)

/** What the watchdog saw. `Ok` is the common case; the three others map to docs/02 §6.2. */
sealed interface WatchdogVerdict {

    data object Ok : WatchdogVerdict

    /** `prepare()` started but no first frame arrived within [timeoutMs] → controller builds a TIMEOUT error. */
    data class PrepareTimeout(val waitedMs: Long, val timeoutMs: Long) : WatchdogVerdict

    /** 长时间无进度: during playback the position stopped advancing for [thresholdMs]. */
    data class NoProgress(val signal: StallSignal, val thresholdMs: Long) : WatchdogVerdict

    /** 卡顿: the engine stayed in buffering for [thresholdMs] without getting anywhere. */
    data class Buffering(val signal: StallSignal, val thresholdMs: Long) : WatchdogVerdict
}

/*
 * Pure playback watchdog (P1-6): no `Looper`, no `Handler`, no `System.currentTimeMillis` — the
 * clock is injected and every observation carries its own timestamp, so the whole thing runs as a
 * plain JVM unit test.
 *
 * The scheduling seam is [nextDeadlineMs]: after every sample the watchdog says when it wants to be
 * asked again, and the controller's coroutine loop sleeps until then on `DispatcherProvider.single`
 * (`delay(nextDeadlineMs - clock.nowMs())`). There is no callback registration here on purpose —
 * that keeps the watchdog free of threading and lets the test drive time by hand.
 *
 * The watchdog only **observes**. It never switches a stream and never throws: it returns a verdict,
 * the controller turns a stall verdict into `FailoverInput.stall` and lets `FailoverPolicy` decide
 * (docs/02 §4.5 C1, §6.2).
 */
class PlaybackWatchdog(
    private val clock: Clock,
    private val config: WatchdogConfig = WatchdogConfig(),
) {

    var phase: WatchdogPhase = WatchdogPhase.IDLE
        private set

    /** The window the current (or next) prepare is judged against; see [onPrepareStart]. */
    var prepareTimeoutMs: Long = config.prepareTimeoutMs
        private set

    private var prepareStartedAtMs = 0L
    private var lastProgressAtMs = 0L
    private var lastPositionMs = 0L
    private var hasPosition = false
    private var bufferingSinceMs: Long? = null
    private var lastSample: PlaybackSample? = null

    /**
     * `prepare()` was sent. Starts the start-up timeout and clears everything from the last stream.
     *
     * [timeoutMs] is the window this one attempt gets; it defaults to [WatchdogConfig.prepareTimeoutMs]
     * (12 s) and is set from the same value the engine's `PlaybackRequest.timeoutMs` carries
     * (SWITCH-P95-1: the first attempt on a channel with a backup asks for a shorter one).
     */
    fun onPrepareStart(nowMs: Long = clock.nowMs(), timeoutMs: Long = config.prepareTimeoutMs) {
        phase = WatchdogPhase.PREPARING
        prepareTimeoutMs = timeoutMs.coerceAtLeast(1L)
        prepareStartedAtMs = nowMs
        lastProgressAtMs = nowMs
        lastPositionMs = 0L
        hasPosition = false
        bufferingSinceMs = null
        lastSample = null
    }

    /** First frame rendered: the start-up timeout is off the table, progress accounting starts. */
    fun onFirstFrame(nowMs: Long = clock.nowMs()) {
        phase = WatchdogPhase.PLAYING
        lastProgressAtMs = nowMs
        lastPositionMs = 0L
        hasPosition = false
        bufferingSinceMs = null
        lastSample = null
    }

    /** Playback stopped / session released: nothing to judge until the next `onPrepareStart`. */
    fun onStopped() {
        phase = WatchdogPhase.STOPPED
        bufferingSinceMs = null
        hasPosition = false
        lastSample = null
    }

    /** Feed one observation and get a verdict. */
    fun onSample(sample: PlaybackSample): WatchdogVerdict {
        lastSample = sample
        return when (phase) {
            WatchdogPhase.IDLE, WatchdogPhase.STOPPED -> WatchdogVerdict.Ok
            WatchdogPhase.PREPARING -> prepareVerdict(sample.nowMs)
            WatchdogPhase.PLAYING, WatchdogPhase.BUFFERING -> playbackVerdict(sample)
        }
    }

    /*
     * Re-judge the last observation at [nowMs] — the tick the controller runs when the timer
     * expires. Kept separate from [onSample] so a caller can wait for the deadline and then ask,
     * instead of sampling on a fixed interval.
     */
    fun poll(nowMs: Long = clock.nowMs()): WatchdogVerdict = when {
        phase == WatchdogPhase.IDLE || phase == WatchdogPhase.STOPPED -> WatchdogVerdict.Ok
        lastSample != null -> onSample(lastSample!!.copy(nowMs = nowMs))
        phase == WatchdogPhase.PREPARING -> prepareVerdict(nowMs)
        else -> WatchdogVerdict.Ok
    }

    /*
     * When the watchdog next needs to be asked, or null when it has nothing to wait for. The
     * controller sleeps until this instant; there is no timer thread inside the watchdog.
     */
    fun nextDeadlineMs(): Long? = when (phase) {
        WatchdogPhase.IDLE, WatchdogPhase.STOPPED -> null
        WatchdogPhase.PREPARING -> prepareStartedAtMs + prepareTimeoutMs
        WatchdogPhase.PLAYING -> lastProgressAtMs + config.progressThresholdMs
        WatchdogPhase.BUFFERING -> (bufferingSinceMs ?: lastProgressAtMs) + config.bufferingThresholdMs
    }

    private fun prepareVerdict(nowMs: Long): WatchdogVerdict {
        val waitedMs = nowMs - prepareStartedAtMs
        return if (waitedMs >= prepareTimeoutMs) {
            WatchdogVerdict.PrepareTimeout(waitedMs, prepareTimeoutMs)
        } else {
            WatchdogVerdict.Ok
        }
    }

    private fun playbackVerdict(sample: PlaybackSample): WatchdogVerdict {
        val advanced = hasPosition && sample.positionMs > lastPositionMs
        val baseline = !hasPosition
        lastPositionMs = sample.positionMs
        hasPosition = true

        // A paused stream holds its position on purpose: refresh the progress anchor, never judge.
        if (sample.isPaused) {
            phase = WatchdogPhase.PLAYING
            bufferingSinceMs = null
            lastProgressAtMs = sample.nowMs
            return WatchdogVerdict.Ok
        }

        if (sample.isLoading) {
            phase = WatchdogPhase.BUFFERING
            // Progress inside a buffering window restarts it — the stream is not stuck.
            if (advanced || baseline || bufferingSinceMs == null) bufferingSinceMs = sample.nowMs
            if (advanced) lastProgressAtMs = sample.nowMs
            val sinceMs = bufferingSinceMs ?: sample.nowMs
            val stalledMs = sample.nowMs - sinceMs
            return if (stalledMs >= config.bufferingThresholdMs) {
                WatchdogVerdict.Buffering(stall(sample, stalledMs), config.bufferingThresholdMs)
            } else {
                WatchdogVerdict.Ok
            }
        }

        phase = WatchdogPhase.PLAYING
        bufferingSinceMs = null
        if (advanced || baseline) {
            lastProgressAtMs = sample.nowMs
            return WatchdogVerdict.Ok
        }
        val stalledMs = sample.nowMs - lastProgressAtMs
        return if (stalledMs >= config.progressThresholdMs) {
            WatchdogVerdict.NoProgress(stall(sample, stalledMs), config.progressThresholdMs)
        } else {
            WatchdogVerdict.Ok
        }
    }

    private fun stall(sample: PlaybackSample, stalledMs: Long): StallSignal =
        StallSignal(stalledMs = stalledMs, positionMs = sample.positionMs, bufferedPositionMs = sample.bufferedPositionMs)
}
