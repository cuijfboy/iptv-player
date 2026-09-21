package ilab.iptv.player.core.player

/**
 * One observation of the running player, read on the engine dispatcher (P1-5 wiring).
 *
 * WHY IT EXISTS: the fail-over watchdog (docs/02 §6.2 "看门狗") judges `playbackPosition /
 * bufferedPosition / isLoading`. The engine's `PlaybackEvent.Stalled` is not enough — a live stream
 * that freezes without an error emits no event at all, which is exactly the case §4.6 calls 缓冲停滞
 * and answers with `Backoff(1s) -> SwitchTo(next)`. So the controller polls this instead of waiting
 * for the engine to notice.
 *
 * Position reads must happen on the engine's single thread (§4.5 C2) — [Media3Engine.sample] is a
 * plain read of `ExoPlayer`, never a `@Volatile` cache, and must not be called off that dispatcher.
 */
data class EngineSample(
    val positionMs: Long,
    val bufferedPositionMs: Long,
    /** `ExoPlayer.playbackState == STATE_BUFFERING` — the `isLoading` of `PlaybackSample`. */
    val isLoading: Boolean,
) {
    companion object {
        /** No engine (or no media loaded yet): zero progress, not buffering. */
        val EMPTY = EngineSample(positionMs = 0L, bufferedPositionMs = 0L, isLoading = false)
    }
}
