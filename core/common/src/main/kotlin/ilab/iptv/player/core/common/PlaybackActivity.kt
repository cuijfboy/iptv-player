package ilab.iptv.player.core.common

import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Is a playback session using the engine right now?" — the process-wide answer the refresh path
 * reads (docs/02 §4.5 C3, §6.1 播放避让 R7).
 *
 * WHY THIS EXISTS AS A SEAM AND NOT AS A HILT BINDING: the producers and the consumers sit in
 * modules that cannot see each other. The flag is written by `:core:player`
 * ([ilab.iptv.player.core.player.PlaybackUiStateMachine], the single writer of `PlaybackUiState`,
 * §4.5 C1); it is read by `:core:source` (the production `PlaybackPrioritySignal`, which halves
 * Fetch/Shallow/Deep concurrency) and by `:app` (the WorkManager layer, which defers a *scheduled*
 * run instead of starting it). `:core:source` may not depend on `:core:player` and `:app` may not
 * depend on either, so no binding graph can connect them — `:core:common` is the one module all
 * three already share.
 *
 * "Active" means the same phase band the playback foreground service treats as foreground
 * (`PlaybackServiceLifecycle`): PREPARING / BUFFERING / PLAYING / FAILOVER. A paused-but-prepared
 * session is *not* active: it is not fetching, not decoding and not competing for bandwidth, which
 * is the only thing R7 protects.
 *
 * Invariants: one writer (the state machine), lock-free read, no Android types, trivially resettable
 * in tests.
 */
object PlaybackActivity {

    private val active = AtomicBoolean(false)

    /** Called by the playback state machine on every state write (not by feature/app code). */
    fun setActive(active: Boolean) {
        this.active.set(active)
    }

    /** True while a session is preparing, buffering, playing or failing over. */
    fun isActive(): Boolean = active.get()
}
