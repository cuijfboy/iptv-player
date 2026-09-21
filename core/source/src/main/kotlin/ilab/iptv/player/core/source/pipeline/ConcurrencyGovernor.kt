package ilab.iptv.player.core.source.pipeline

/**
 * R7 playback avoidance (docs/02 §4.5 C3, §6.1 "播放避让"): while a playback session is active and
 * the run asked for [respectPlayback], the Fetch / Shallow / Deep concurrency is **halved** so the
 * refresh never competes for bandwidth or CPU with what the user is watching.
 *
 * The playback state arrives through [PlaybackPrioritySignal] — a narrow, injectable seam. Wiring it
 * to the real `PlaybackController` is P1-4's job; until that lands the production binding returns
 * `false` (see `SourceModule` and the report's open items).
 */
fun interface PlaybackPrioritySignal {
    fun isPlaybackActive(): Boolean
}

class ConcurrencyGovernor(
    private val limits: PipelineLimits,
    private val playback: PlaybackPrioritySignal,
    private val respectPlayback: Boolean,
) {
    fun fetchConcurrency(): Int = effective(limits.fetchConcurrency)
    fun shallowConcurrency(): Int = effective(limits.shallowConcurrency)
    fun deepConcurrency(): Int = effective(limits.deepConcurrency)

    /** The multiplier in force right now: 1 normally, 0.5 while playing (never below one worker). */
    fun effective(base: Int): Int =
        if (respectPlayback && playback.isPlaybackActive()) maxOf(1, base / 2) else base
}
