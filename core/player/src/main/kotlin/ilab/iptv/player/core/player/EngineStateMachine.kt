package ilab.iptv.player.core.player

import ilab.iptv.player.core.model.EngineState

/**
 * Coarse player phase, independent of media3's int constants so the transition table is unit-testable
 * without Android. `Media3Engine` maps `Player.STATE_*` onto this.
 */
enum class PlayerPhase { IDLE, BUFFERING, READY, ENDED }

/**
 * The engine-side state machine of docs/02 §6.2. It only ever produces [EngineState]; the
 * business state (failover included) belongs to `PlaybackController` (docs/02 §4.5 C1).
 *
 * RULES
 * - [EngineState.RELEASED] is terminal: after `release()` nothing moves the state again (P5).
 * - [EngineState.ERROR] is sticky until the next `prepare` (the diagram's ERROR → FAILOVER edge).
 * - `READY` while already `PLAYING` keeps `PLAYING` (media3 stays READY during normal playback).
 */
class EngineStateMachine {

    var state: EngineState = EngineState.IDLE
        private set

    fun onPreparing(): EngineState = move(EngineState.PREPARING)

    fun onPlayerPhase(phase: PlayerPhase): EngineState = when {
        state == EngineState.RELEASED -> state
        state == EngineState.ERROR -> state
        phase == PlayerPhase.BUFFERING -> move(EngineState.BUFFERING)
        phase == PlayerPhase.READY -> if (state == EngineState.PLAYING) state else move(EngineState.READY)
        phase == PlayerPhase.ENDED -> move(EngineState.ENDED)
        // A bare IDLE means `stop()`/`clearMediaItems()` — back to the start.
        else -> move(EngineState.IDLE)
    }

    fun onIsPlaying(isPlaying: Boolean): EngineState = when {
        state == EngineState.RELEASED || state == EngineState.ERROR -> state
        isPlaying && (state == EngineState.READY || state == EngineState.BUFFERING) -> move(EngineState.PLAYING)
        !isPlaying && state == EngineState.PLAYING -> move(EngineState.READY)
        else -> state
    }

    fun onError(): EngineState = if (state == EngineState.RELEASED) state else move(EngineState.ERROR)

    fun onReleased(): EngineState = move(EngineState.RELEASED)

    private fun move(next: EngineState): EngineState {
        state = next
        return next
    }
}
