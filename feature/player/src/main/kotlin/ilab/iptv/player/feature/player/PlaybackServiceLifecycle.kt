package ilab.iptv.player.feature.player

import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState

/**
 * When the playback foreground service is in the foreground, and when it goes away (P1-7 item 1).
 *
 * WHY IT IS PURE: the interesting part is not `startForeground(...)` — it is *which* transition may
 * call it. Android forbids starting a foreground service from the background (API 31+), calls it
 * without a notification are a system crash, and leaving a "playing" notification up after playback
 * stopped is a lie the user sees. So the transition table is a pure class the JVM tests can walk
 * through every phase, and [PlaybackService] only executes the [ServiceAction] it returns.
 *
 * THE THREE BANDS
 *  - a session that is being prepared / buffered / playing / failing over → **foreground** with an
 *    ongoing notification (this is the row that stops the TV from freezing the process, docs/02 §7.2 P4);
 *  - a session that ended in a failure → notification **removed**, service kept started (the failure
 *    overlay's "重试" and the network-restore retry both re-enter playback without a new Activity);
 *  - a session that stopped or was released → notification removed **and** the service stops itself,
 *    which is what "停止播放时正确收尾" means (nothing of ours is left running).
 */
class PlaybackServiceLifecycle {

    /** What the service should do now; every field is an instruction, not a state. */
    data class ServiceAction(
        val startForeground: Boolean = false,
        val updateNotification: Boolean = false,
        val removeNotification: Boolean = false,
        val stopSelf: Boolean = false,
    ) {
        /** Nothing to do: the service is already in the shape this state asks for. */
        val isNoOp: Boolean
            get() = !startForeground && !updateNotification && !removeNotification && !stopSelf
    }

    enum class State {
        /** Started (by the player screen) but nothing is playing: no notification, no foreground. */
        IDLE,

        /** Foreground with an ongoing notification. */
        FOREGROUND,

        /** Started, nothing playing, nothing shown — a failure waiting for a retry. */
        BACKGROUND,

        /** Playback ended; the service is stopping itself. Terminal until the next `onStartCommand`. */
        STOPPED,
    }

    var state: State = State.IDLE
        private set

    /** Map the playback state the screen renders onto the service's next action. */
    fun onPlaybackState(ui: PlaybackUiState): ServiceAction = when (ui.phase) {
        PlaybackPhase.PREPARING,
        PlaybackPhase.BUFFERING,
        PlaybackPhase.PLAYING,
        PlaybackPhase.FAILOVER,
        -> when (state) {
            State.FOREGROUND -> ServiceAction(updateNotification = true)
            State.IDLE, State.BACKGROUND, State.STOPPED -> {
                state = State.FOREGROUND
                ServiceAction(startForeground = true)
            }
        }

        // A failure is not "playing": the notification must go, but the process stays up so the
        // retry paths (button / network restore) can bring playback back without the screen.
        PlaybackPhase.ERROR -> when (state) {
            State.FOREGROUND -> {
                state = State.BACKGROUND
                ServiceAction(removeNotification = true)
            }

            else -> ServiceAction()
        }

        // IDLE is special: it is ALSO the state the session reports before anything was ever opened
        // (the service starts, the state flow immediately emits its initial value), and stopping the
        // service on that would tear it down the moment it was created.
        PlaybackPhase.IDLE -> when (state) {
            State.IDLE, State.STOPPED -> ServiceAction()
            else -> {
                state = State.STOPPED
                ServiceAction(removeNotification = true, stopSelf = true)
            }
        }

        PlaybackPhase.STOPPED, PlaybackPhase.RELEASED -> when (state) {
            // Same guard as IDLE above: a session that had already stopped when the screen opened must
            // not stop the service the screen just asked for.
            State.IDLE, State.STOPPED -> ServiceAction()
            else -> {
                state = State.STOPPED
                ServiceAction(removeNotification = true, stopSelf = true)
            }
        }
    }

    /**
     * The service itself is being destroyed (system reclaim, task removed, `stopService`): the
     * caller must drop the notification explicitly, which is a no-op once [state] is STOPPED.
     */
    fun onDestroyed(): ServiceAction {
        if (state == State.STOPPED) return ServiceAction()
        state = State.STOPPED
        return ServiceAction(removeNotification = true)
    }

    /**
     * The player screen asked the service to run (`startForegroundService`), before the first playback
     * state exists.
     *
     * Android gives a started-foreground service seconds to post a notification, and the session may
     * still be resolving its channel at that moment, so the service goes foreground immediately and
     * the state observer replaces the placeholder as soon as there is something truer to show.
     */
    fun onSessionRequested(): ServiceAction = when (state) {
        State.FOREGROUND -> ServiceAction(updateNotification = true)
        State.IDLE, State.BACKGROUND, State.STOPPED -> {
            state = State.FOREGROUND
            ServiceAction(startForeground = true)
        }
    }
}
