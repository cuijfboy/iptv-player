package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EngineState
import org.junit.Test

/** The engine-side state machine of docs/02 §6.2 / §4.5 C1. */
class EngineStateMachineTest {

    @Test
    fun `starts idle`() {
        assertThat(EngineStateMachine().state).isEqualTo(EngineState.IDLE)
    }

    @Test
    fun `prepare moves to preparing`() {
        val machine = EngineStateMachine()
        assertThat(machine.onPreparing()).isEqualTo(EngineState.PREPARING)
        assertThat(machine.state).isEqualTo(EngineState.PREPARING)
    }

    @Test
    fun `player phases map onto engine states`() {
        val machine = EngineStateMachine()
        machine.onPreparing()
        assertThat(machine.onPlayerPhase(PlayerPhase.BUFFERING)).isEqualTo(EngineState.BUFFERING)
        assertThat(machine.onPlayerPhase(PlayerPhase.READY)).isEqualTo(EngineState.READY)
        assertThat(machine.onPlayerPhase(PlayerPhase.ENDED)).isEqualTo(EngineState.ENDED)
        assertThat(machine.onPlayerPhase(PlayerPhase.IDLE)).isEqualTo(EngineState.IDLE)
    }

    @Test
    fun `playing only lifts ready or buffering`() {
        val machine = EngineStateMachine()
        assertThat(machine.onIsPlaying(true)).isEqualTo(EngineState.IDLE)
        machine.onPreparing()
        machine.onPlayerPhase(PlayerPhase.READY)
        assertThat(machine.onIsPlaying(true)).isEqualTo(EngineState.PLAYING)
        // media3 keeps reporting READY during playback; that must not knock the state back down.
        assertThat(machine.onPlayerPhase(PlayerPhase.READY)).isEqualTo(EngineState.PLAYING)
        assertThat(machine.onIsPlaying(false)).isEqualTo(EngineState.READY)
    }

    @Test
    fun `pause outside playing leaves the state alone`() {
        val machine = EngineStateMachine()
        machine.onPreparing()
        machine.onPlayerPhase(PlayerPhase.READY)
        assertThat(machine.onIsPlaying(false)).isEqualTo(EngineState.READY)
    }

    @Test
    fun `error is sticky until the next prepare`() {
        val machine = EngineStateMachine()
        machine.onPreparing()
        assertThat(machine.onError()).isEqualTo(EngineState.ERROR)
        assertThat(machine.onPlayerPhase(PlayerPhase.READY)).isEqualTo(EngineState.ERROR)
        assertThat(machine.onIsPlaying(true)).isEqualTo(EngineState.ERROR)
        assertThat(machine.onPreparing()).isEqualTo(EngineState.PREPARING)
    }

    @Test
    fun `released is terminal`() {
        val machine = EngineStateMachine()
        machine.onPreparing()
        machine.onPlayerPhase(PlayerPhase.READY)
        assertThat(machine.onReleased()).isEqualTo(EngineState.RELEASED)
        assertThat(machine.onPlayerPhase(PlayerPhase.READY)).isEqualTo(EngineState.RELEASED)
        assertThat(machine.onIsPlaying(true)).isEqualTo(EngineState.RELEASED)
        assertThat(machine.onError()).isEqualTo(EngineState.RELEASED)
    }
}
