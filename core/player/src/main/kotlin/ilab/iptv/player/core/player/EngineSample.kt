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
    /**
     * `ExoPlayer.playWhenReady` — "playback was asked for and has not been cancelled" (P1-7).
     *
     * The remote's play/pause key, the notification's control and the TV's system media control all
     * pause the player *through the MediaSession*, which the controller's own "the user pressed pause"
     * flag never sees. Without this field a MediaSession-driven pause looks exactly like a frozen
     * stream to the fail-over watchdog, which then "rescues" a channel the user deliberately paused.
     *
     * It is deliberately `playWhenReady` and not `isPlaying`: a live stream that freezes keeps
     * `playWhenReady = true` (the player still wants to play, the data stopped coming), and that is
     * exactly the case `PLAY_STALL` exists for. The default is `true` so a sample that does not carry
     * the field can never be mistaken for a pause.
     */
    val playWhenReady: Boolean = true,
) {
    /**
     * The stream is not advancing because *someone paused it*: a live source that stalls keeps
     * `playWhenReady = true`, so this cannot mask the case the watchdog exists for — it only stops a
     * MediaSession pause from being answered with a fail-over.
     */
    val isPausedByUser: Boolean get() = !playWhenReady

    companion object {
        /** No engine (or no media loaded yet): zero progress, not buffering. */
        val EMPTY = EngineSample(positionMs = 0L, bufferedPositionMs = 0L, isLoading = false)
    }
}
