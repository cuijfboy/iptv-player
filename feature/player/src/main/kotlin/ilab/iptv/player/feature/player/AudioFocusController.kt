package ilab.iptv.player.feature.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import ilab.iptv.player.core.player.AudioFocusDecision
import ilab.iptv.player.core.player.AudioFocusEvent
import ilab.iptv.player.core.player.AudioFocusPolicy

/**
 * The Android half of audio focus (P1-7 item 3): request `AUDIOFOCUS_GAIN` when playback starts, hand
 * every focus change to [AudioFocusPolicy], and release the request when the session ends.
 *
 * The engine does NOT handle focus (`Media3Engine` builds ExoPlayer with `handleAudioFocus = false`),
 * so request and abandon are a pair owned here: a service that requests and dies without abandoning
 * leaves the TV's next app without audio, which is why [release] is on every exit path of
 * [PlaybackService].
 *
 * API levels: `AudioFocusRequest` is API 26+, the legacy `requestAudioFocus(listener, stream, gain)`
 * is API 21+. minSdk is 21, so both live behind one small interface and the newer one is only reached
 * inside a `@RequiresApi(26)` class — lint runs with `abortOnError`, so the split is deliberate.
 */
class AudioFocusController(
    context: Context,
    private val policy: AudioFocusPolicy = AudioFocusPolicy(),
    private val target: Target,
    private val onDecision: (AudioFocusEvent, AudioFocusDecision) -> Unit = { _, _ -> },
) {

    /** What the policy's decision is applied to: the playback session, through the service. */
    interface Target {
        fun pauseForFocusLoss()
        fun resumeAfterFocusGain()
        fun setDucked(ducked: Boolean)
    }

    private val appContext = context.applicationContext

    /** Focus changes are delivered on the main looper: the service is a main-thread component. */
    private val handler = Handler(Looper.getMainLooper())

    private val listener = AudioManager.OnAudioFocusChangeListener { change -> apply(eventOf(change)) }

    private val backend: Backend = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Api26Backend(appContext, handler, listener)
    } else {
        LegacyBackend(appContext, listener)
    }

    /** True while we hold the request; the service asks before treating a loss as a real one. */
    val holdsFocus: Boolean get() = backend.held

    /**
     * Request `AUDIOFOCUS_GAIN`. Returns whether the request was granted: a denied request means the
     * caller must not start playback, because the TV has already given the output to someone else.
     */
    fun acquire(): Boolean {
        val granted = backend.acquire()
        // A granted request is the policy's GAIN: it lifts a duck we were carrying and clears the
        // "suspended" bookkeeping, so a long-running session never stays stuck thinking it is silent.
        if (granted) apply(AudioFocusEvent.GAIN)
        return granted
    }

    /**
     * Release the request (session stop / service destroyed) and let the policy close its books.
     * Idempotent: an already-released controller does nothing.
     */
    fun release() {
        if (!backend.held) return
        onDecision(AudioFocusEvent.LOSS, policy.onSessionEnded())
        backend.abandon()
    }

    private fun apply(event: AudioFocusEvent) {
        val decision = policy.onEvent(event)
        // Order matters: a duck that was upgraded to a pause must come back at full volume *before* it
        // resumes, otherwise the stream resumes at 20 % and stays there.
        if (decision.restoreVolume) target.setDucked(false)
        if (decision.duck) target.setDucked(true)
        if (decision.pause) target.pauseForFocusLoss()
        if (decision.resume) target.resumeAfterFocusGain()
        if (decision.abandonFocus) {
            backend.abandon()
        }
        onDecision(event, decision)
    }

    private fun eventOf(change: Int): AudioFocusEvent = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusEvent.GAIN
        AudioManager.AUDIOFOCUS_LOSS -> AudioFocusEvent.LOSS
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusEvent.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK
        // An unknown code is not guessed at: treating it as GAIN would resume playback nobody asked for.
        else -> AudioFocusEvent.LOSS_TRANSIENT
    }

    /** The two platform shapes behind one call. */
    private interface Backend {
        val held: Boolean
        fun acquire(): Boolean
        fun abandon()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private class Api26Backend(
        context: Context,
        handler: Handler,
        listener: AudioManager.OnAudioFocusChangeListener,
    ) : Backend {

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            // A live stream must not be ducked by the system: the app owns the duck decision
            // (AudioFocusPolicy) and accepts it only on the CAN_DUCK row.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()

        override var held: Boolean = false
            private set

        override fun acquire(): Boolean {
            held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return held
        }

        override fun abandon() {
            audioManager.abandonAudioFocusRequest(request)
            held = false
        }
    }

    @Suppress("DEPRECATION") // The only API 21–25 shape that exists.
    private class LegacyBackend(
        context: Context,
        private val listener: AudioManager.OnAudioFocusChangeListener,
    ) : Backend {

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        override var held: Boolean = false
            private set

        override fun acquire(): Boolean {
            // The legacy overload takes no Handler: the callback lands on the looper of the calling
            // thread, and this controller is built from the service's main thread.
            held = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return held
        }

        override fun abandon() {
            audioManager.abandonAudioFocus(listener)
            held = false
        }
    }
}
