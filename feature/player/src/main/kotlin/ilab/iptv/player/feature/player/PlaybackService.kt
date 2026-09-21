package ilab.iptv.player.feature.player

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.player.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground playback service (P1-7 items 1 and 2, docs/02 §7.2 P1/P4).
 *
 * WHAT IT OWNS
 *  - the `MediaSessionService` half: the remote's `KEYCODE_MEDIA_PLAY`/`PAUSE`, the TV's system media
 *    control and any other media controller all arrive here and act on the ONE engine instance;
 *  - the foreground/notification half: while a session is being prepared, buffered, playing or
 *    failing over, the service is a `mediaPlayback` foreground service with an ongoing notification,
 *    which is what stops the TV from freezing or reclaiming the process mid-stream (docs/02 §7.2 P4).
 *    The transition table is [PlaybackServiceLifecycle]; this class only executes its answers;
 *  - audio focus ([AudioFocusController] + the pure `AudioFocusPolicy` of `:core:player`).
 *
 * WHERE IT LIVES (deviation, reported to god): docs/02 §7.2 P1 names `:app`'s `PlaybackService`, but
 * §3.2 rule 3 forbids `:app` from declaring `:core:player` — and this service's whole job is that
 * module's session. `:feature:player` is the only module the matrix lets see `:core:player`
 * (rule 4), so the service lives here, exactly like P1-5's fail-over wiring.
 *
 * THREADING: `MediaSessionService` calls back on the main thread; the engine lives on its own
 * HandlerThread (§4.5 C2). The MediaSession is built around the engine's own `Player`, so Media3
 * marshals every player call onto that looper — the service never touches the player directly.
 */
class PlaybackService : MediaSessionService() {

    /**
     * Hilt's `@AndroidEntryPoint` only accepts a class whose direct superclass is `Service` (it does
     * not walk the chain, so `MediaSessionService` is rejected), so the session comes from an entry
     * point instead. Same graph, same scope — `PlaybackSession` is a `@Singleton`.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun playbackSession(): PlaybackSession

        /** The event emitter of docs/03 §3.3.1 needs the same logger the rest of the app uses. */
        fun logger(): Logger
    }

    private val entryPoint: ServiceEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
    }

    private val session: PlaybackSession by lazy { entryPoint.playbackSession() }

    private val events: PlaybackSystemEvents by lazy { PlaybackSystemEvents(entryPoint.logger()) }

    private val lifecycle = PlaybackServiceLifecycle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stateObserver: Job? = null
    private var mediaSession: MediaSession? = null
    private var audioFocus: AudioFocusController? = null

    override fun onCreate() {
        super.onCreate()
        PlaybackNotifications.ensureChannel(this)
        audioFocus = AudioFocusController(
            context = this,
            target = focusTarget,
            onDecision = { event, decision -> events.focusChanged(event, decision) },
        )
        // The session must exist before Media3 can be asked for it, and `addSession` is what makes
        // Media3 drive the notification/foreground callbacks for it (P1-7 item 1).
        ensureMediaSession()?.let(::addSession)
        stateObserver = scope.launch {
            session.state.collect { ui ->
                apply(lifecycle.onPlaybackState(ui))
                syncFocus(ui.phase)
            }
        }
    }

    /**
     * Media3 asks for the session whenever a controller connects (the system's media control, the
     * TV's notification shade, another app's `MediaController`). One session, built once.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        ensureMediaSession()

    /**
     * The player screen starts this service when it opens a channel, and the notification's two
     * buttons come back as start commands. `@CallSuper` — Media3's own handling of
     * `ACTION_MEDIA_BUTTON` must still run.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PlaybackNotifications.ACTION_TOGGLE -> togglePlayback()
            PlaybackNotifications.ACTION_STOP -> session.stop(reason = "notification-stop")
            else -> Unit
        }
        // A foreground service started with `startForegroundService` must post a notification within
        // seconds: this is that notification, and the playback state replaces its content.
        apply(lifecycle.onSessionRequested())
        syncFocus(session.state.value.phase)
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * We own the notification (P1-7 item 1: the channel name, the fail-over hint and the failure text
     * are ours, not Media3's), so the default provider never runs. Media3 still calls this on every
     * player event, which is a free consistency check: re-render whatever the lifecycle says now.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (lifecycle.state != PlaybackServiceLifecycle.State.FOREGROUND) return
        postNotification(currentContent())
    }

    override fun onDestroy() {
        stateObserver?.cancel()
        stateObserver = null
        // A service torn down while it was foreground (system reclaim, task removed, `stopService`)
        // never runs the STOPPED row below, so report the stop here or the foreground/stop pair would
        // be unbalanced in the log.
        if (lifecycle.state == PlaybackServiceLifecycle.State.FOREGROUND) {
            val ui = session.state.value
            events.playbackServiceStopped("service-destroyed", ui.channelId, ui.activeStreamId)
        }
        apply(lifecycle.onDestroyed())
        // Focus before the session: a released focus request outliving a paused player is the failure
        // the next app on the TV would pay for.
        audioFocus?.release()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle execution

    private fun apply(action: PlaybackServiceLifecycle.ServiceAction) {
        if (action.removeNotification) {
            audioFocus?.release()
            removeNotification()
        }
        if (action.startForeground) {
            val ui = session.state.value
            try {
                postNotification(currentContent())
                events.playbackServiceStarted(ui.channelId, ui.activeStreamId, ui.phase.name)
            } catch (denied: Throwable) {
                // API 31+ rejects a background foreground-service start; nothing is on screen, so the
                // event is the only place this failure can be seen (docs/03 §3.3.1).
                events.playbackServiceStartFailed(ui.phase.name, denied)
                throw denied
            }
        } else if (action.updateNotification) {
            postNotification(currentContent())
        }
        // The notification is already gone (`removeNotification` above); this is the second half of
        // "停止播放时正确收尾": nothing of ours is left running.
        if (action.stopSelf) {
            val ui = session.state.value
            events.playbackServiceStopped("phase-${ui.phase.name.lowercase()}", ui.channelId, ui.activeStreamId)
            stopSelf()
        }
    }

    /**
     * The audio focus request follows *actual playback*, and that detail is a device finding, not a
     * preference.
     *
     * The first implementation requested focus whenever the service refreshed its notification, which
     * meant: another app takes the focus → the policy pauses and gives the focus up → the pause moves
     * the session out of `PLAYING` → the notification updates → focus is requested again → the new
     * grant is a `GAIN` → the policy resumes playback. On the TV that showed up as this app and VLC
     * fighting over the audio output (measured 2026-09-22: VLC asked for focus at 01:39:03, we
     * abandoned at 01:39:05.703 and re-requested 13 ms later, and VLC gave up at 01:39:07).
     *
     * Holding the request exactly while `PLAYING` fixes that and keeps both directions working: a
     * permanent loss leaves us paused and silent (nothing re-acquires), and the user asking for
     * playback again — the remote's play key through the MediaSession, or the notification's button —
     * moves the session back to `PLAYING`, which is what re-requests the focus.
     */
    private fun syncFocus(phase: PlaybackPhase) {
        val focus = audioFocus ?: return
        if (phase == PlaybackPhase.PLAYING && !focus.holdsFocus) focus.acquire()
    }

    private fun postNotification(content: PlaybackNotificationContent) {
        ServiceCompat.startForeground(
            this,
            PlaybackNotifications.NOTIFICATION_ID,
            PlaybackNotifications.build(this, content),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    private fun removeNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun currentContent(): PlaybackNotificationContent =
        PlaybackNotificationContent.of(session.state.value, session.engineState.value)
            ?: PlaybackNotificationContent.PLACEHOLDER

    /**
     * The notification's "play/pause" button. It reads the engine's live state instead of a cached
     * one, so a button that raced the remote still ends in the state the user asked for.
     */
    private fun togglePlayback() {
        if (session.engineState.value == EngineState.PLAYING) session.pause() else session.play()
    }

    // ---------------------------------------------------------------- audio focus target

    private val focusTarget = object : AudioFocusController.Target {
        override fun pauseForFocusLoss() = session.pause()

        override fun resumeAfterFocusGain() = session.play()

        override fun setDucked(ducked: Boolean) = session.setDucked(ducked)
    }

    private fun ensureMediaSession(): MediaSession? {
        mediaSession?.let { return it }
        val player = session.mediaSessionPlayer() ?: return null
        return MediaSession.Builder(this, player).build().also { mediaSession = it }
    }

    companion object {

        /**
         * Start (or re-start) the service from the player screen. `startForegroundService` is correct
         * here because the screen is in the foreground when it asks, which is exactly the condition
         * Android 12 puts on starting a foreground service.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context))
        }

        /** The playback session is over (screen closed): take the service and its notification down. */
        fun stop(context: Context) {
            context.stopService(intent(context))
        }

        private fun intent(context: Context): Intent =
            Intent(context, PlaybackService::class.java)
    }
}
