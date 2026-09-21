package ilab.iptv.player.core.player

/**
 * Audio focus for the foreground playback service (P1-7 item 3, docs/02 §7.5 "系统集成").
 *
 * The engine deliberately does **not** let ExoPlayer handle focus
 * (`setAudioAttributes(..., handleAudioFocus = false)`, `Media3Engine.buildPlayer`), so the app owns
 * the request/release pair and can decide what a loss means. That decision is a policy, not
 * plumbing, which is why it lives in a pure class the JVM tests drive directly.
 *
 * THE TRADEOFF (written out because the work package asks for it, and repeated in the verification
 * file):
 *  - `LOSS_TRANSIENT_CAN_DUCK` → **duck** (turn the volume down, keep decoding). A live channel is a
 *    live edge: pausing it means the buffer is stale by the time the user comes back, and the
 *    "resume" then costs a re-buffer (S5: ~0.9 s to first frame on a good source, seconds on a bad
 *    one). Ducking keeps the user's channel where they left it. The cost is that the app keeps using
 *    bandwidth while another app talks, which is why this is limited to the "can duck" row — the two
 *    coercive rows below pause.
 *  - `LOSS_TRANSIENT` → **pause** and remember to resume on `GAIN`. The other app needs the output
 *    device exclusively (e.g. a call), so staying silent-but-buffering would be pointless.
 *  - `LOSS` (permanent) → **pause, abandon focus and do not resume**. Whoever took the focus owns it
 *    until the user asks us to play again; resuming on the next `GAIN` would fight the new owner.
 */
class AudioFocusPolicy(private val duckVolume: Float = DEFAULT_DUCK_VOLUME) {

    /** What the service is doing with the audio output right now. */
    enum class State { ACTIVE, DUCKED, SUSPENDED }

    var state: State = State.ACTIVE
        private set

    /** The volume a duck applies; the service restores 1.0 when the duck is lifted. */
    val duckLevel: Float get() = duckVolume

    /**
     * Apply one focus event. Always call this for `AUDIOFOCUS_GAIN` — it is the only event that
     * lifts a duck or resumes a transient pause.
     */
    fun onEvent(event: AudioFocusEvent): AudioFocusDecision = when (event) {
        AudioFocusEvent.GAIN -> onGain()
        AudioFocusEvent.LOSS -> {
            state = State.SUSPENDED
            AudioFocusDecision(pause = true, abandonFocus = true)
        }

        AudioFocusEvent.LOSS_TRANSIENT -> {
            state = State.SUSPENDED
            AudioFocusDecision(pause = true)
        }

        AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK -> {
            // A duck while we are already silent would only fight the new owner's volume.
            if (state == State.SUSPENDED) AudioFocusDecision() else {
                state = State.DUCKED
                AudioFocusDecision(duck = true, volume = duckVolume)
            }
        }
    }

    /**
     * The playback session ended (user stop / screen closed): the focus request must be released,
     * otherwise the next app is stuck behind a holder that is not playing anything.
     */
    fun onSessionEnded(): AudioFocusDecision {
        state = State.SUSPENDED
        return AudioFocusDecision(abandonFocus = true)
    }

    private fun onGain(): AudioFocusDecision = when (state) {
        State.ACTIVE -> AudioFocusDecision()
        // A transient loss paused us: resume, and restore the volume in the same step (a duck that
        // was upgraded to a pause must not come back at 20 %).
        State.SUSPENDED -> {
            state = State.ACTIVE
            AudioFocusDecision(resume = true, restoreVolume = true)
        }

        State.DUCKED -> {
            state = State.ACTIVE
            AudioFocusDecision(restoreVolume = true)
        }
    }

    companion object {
        /** Quiet enough to be clearly "under" the other app, loud enough to follow the channel. */
        const val DEFAULT_DUCK_VOLUME = 0.2f

        /** Normal playback volume. */
        const val FULL_VOLUME = 1.0f
    }
}

/** The focus events the service translates AudioManager's focus-change codes into. */
enum class AudioFocusEvent { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

/**
 * What the service must do to playback. Plain flags instead of a sealed hierarchy because one event
 * can ask for two things at once (`GAIN` after a duck-requested pause resumes *and* restores).
 */
data class AudioFocusDecision(
    val pause: Boolean = false,
    val resume: Boolean = false,
    val duck: Boolean = false,
    val restoreVolume: Boolean = false,
    val abandonFocus: Boolean = false,
    /** Non-null only for a duck: the volume to apply. */
    val volume: Float? = null,
)
