package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState
import org.junit.Test

/**
 * P1-7 item 1: the foreground-service transition table.
 *
 * Three platform rules make this worth testing rather than eyeballing on the device: a
 * `startForegroundService` that posts nothing within seconds is killed, starting a foreground service
 * from the background throws on Android 12+, and a notification that says "playing" after playback
 * stopped is a visible lie. Each test below is one of those rules.
 */
class PlaybackServiceLifecycleTest {

    @Test
    fun `the player screen's request goes foreground immediately, before any state exists`() {
        val lifecycle = PlaybackServiceLifecycle()

        val action = lifecycle.onSessionRequested()

        assertThat(action.startForeground).isTrue()
        assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.FOREGROUND)
    }

    @Test
    fun `every active playback phase keeps the service in the foreground`() {
        val active = listOf(
            PlaybackPhase.PREPARING,
            PlaybackPhase.BUFFERING,
            PlaybackPhase.PLAYING,
            PlaybackPhase.FAILOVER,
        )

        active.forEach { phase ->
            val lifecycle = PlaybackServiceLifecycle()
            lifecycle.onSessionRequested()

            val action = lifecycle.onPlaybackState(state(phase))

            assertThat(action.isNoOp).isFalse()
            assertThat(action.updateNotification).isTrue()
            assertThat(action.removeNotification).isFalse()
            assertThat(action.stopSelf).isFalse()
            assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.FOREGROUND)
        }
    }

    @Test
    fun `a playback phase reached without a request still goes foreground`() {
        // The retry paths (failure overlay's 重试, network restore) re-enter playback with the service
        // started but not in the foreground; the notification has to come back by itself.
        val lifecycle = PlaybackServiceLifecycle()

        val action = lifecycle.onPlaybackState(state(PlaybackPhase.PLAYING))

        assertThat(action.startForeground).isTrue()
        assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.FOREGROUND)
    }

    @Test
    fun `a failure drops the notification but keeps the service alive for the retry`() {
        val lifecycle = PlaybackServiceLifecycle()
        lifecycle.onSessionRequested()

        val action = lifecycle.onPlaybackState(state(PlaybackPhase.ERROR))

        assertThat(action.removeNotification).isTrue()
        assertThat(action.stopSelf).isFalse()
        assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.BACKGROUND)

        // …and the retry brings it straight back.
        val retry = lifecycle.onPlaybackState(state(PlaybackPhase.PREPARING))
        assertThat(retry.startForeground).isTrue()
        assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.FOREGROUND)
    }

    @Test
    fun `stopping playback removes the notification and stops the service`() {
        val stopPhases = listOf(PlaybackPhase.STOPPED, PlaybackPhase.RELEASED, PlaybackPhase.IDLE)

        stopPhases.forEach { phase ->
            val lifecycle = PlaybackServiceLifecycle()
            lifecycle.onSessionRequested()

            val action = lifecycle.onPlaybackState(state(phase))

            assertThat(action.removeNotification).isTrue()
            assertThat(action.stopSelf).isTrue()
            assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.STOPPED)
        }
    }

    @Test
    fun `the session's initial idle state does not tear the fresh service down`() {
        // `PlaybackSession.state` is a StateFlow: the service's observer sees its current value the
        // moment it subscribes — and the screen may open right after a previous session stopped.
        listOf(PlaybackPhase.IDLE, PlaybackPhase.STOPPED, PlaybackPhase.RELEASED).forEach { phase ->
            val lifecycle = PlaybackServiceLifecycle()

            val action = lifecycle.onPlaybackState(state(phase))

            assertThat(action.isNoOp).isTrue()
            assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.IDLE)
        }
    }

    @Test
    fun `repeating the same state does not churn the notification`() {
        val lifecycle = PlaybackServiceLifecycle()
        lifecycle.onSessionRequested()
        lifecycle.onPlaybackState(state(PlaybackPhase.PLAYING))

        // Same phase again (a position update, a re-emitted engine state): no new foreground call and
        // no second notification.
        assertThat(lifecycle.onPlaybackState(state(PlaybackPhase.PLAYING)).stopSelf).isFalse()

        val stopped = lifecycle.onPlaybackState(state(PlaybackPhase.STOPPED))
        assertThat(stopped.stopSelf).isTrue()
        assertThat(lifecycle.onPlaybackState(state(PlaybackPhase.STOPPED)).isNoOp).isTrue()
    }

    @Test
    fun `a fresh request after a stop starts a new foreground session`() {
        val lifecycle = PlaybackServiceLifecycle()
        lifecycle.onSessionRequested()
        lifecycle.onPlaybackState(state(PlaybackPhase.STOPPED))

        val action = lifecycle.onSessionRequested()

        assertThat(action.startForeground).isTrue()
        assertThat(lifecycle.state).isEqualTo(PlaybackServiceLifecycle.State.FOREGROUND)
    }

    @Test
    fun `destroying the service takes the notification down once`() {
        val lifecycle = PlaybackServiceLifecycle()
        lifecycle.onSessionRequested()

        val first = lifecycle.onDestroyed()
        val second = lifecycle.onDestroyed()

        assertThat(first.removeNotification).isTrue()
        assertThat(second.isNoOp).isTrue()
    }

    private fun state(phase: PlaybackPhase) = PlaybackUiState.EMPTY.copy(phase = phase)
}
