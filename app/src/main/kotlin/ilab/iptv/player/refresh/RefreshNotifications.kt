package ilab.iptv.player.refresh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import ilab.iptv.player.R

/**
 * The Android half of the refresh notification (docs/04 P2-5 item 2): channel, id, and the
 * `Notification` the foreground service shows while a run is in flight.
 *
 * NOT 不打扰 (the acceptance line) IS ENFORCED HERE, not by convention:
 * - the channel is `IMPORTANCE_LOW` and silent — on Android 8+ importance is a *user-visible*
 *   property of the channel, so an accidental `IMPORTANCE_DEFAULT` would beep on every 06:00 run;
 * - `setOnlyAlertOnce(true)` + `setSilent(true)` + no sound/vibration on the channel, so the
 *   per-progress updates (one per pipeline phase) cannot alert at all;
 * - `setOngoing(true)` while it runs, so a swipe cannot leave a job with no visible owner; the
 *   notification is removed the moment the worker finishes (`RefreshWorker` returns, WorkManager
 *   drops the foreground state), which is "结束即撤".
 *
 * The foreground service itself is WorkManager's `SystemForegroundService` (`dataSync` type): a
 * background-started `Service.startForeground` is forbidden from API 31 on, while WorkManager's
 * long-running-worker path is the supported way to ask for exactly this notification. See the
 * verification file for the reasoning and the manifest entries.
 *
 * WHY [ensureChannel] IS CALLED BY THE WORKER AND NOT BY A STARTUP HOOK (NEW-20260922-003): the
 * channel must exist *before* anything asks the platform for the foreground state, and the three
 * ways a refresh starts (first-run wizard, manual, 06:00 schedule) all converge on
 * [RefreshWorker.doWork] — so one call there covers every entry, and it covers "user cleared app
 * data" / "channel deleted in settings" without depending on which process happened to start first.
 * The same shape as the playback side, where [PlaybackService] ensures its channel in `onCreate`
 * immediately before it goes foreground.
 */
object RefreshNotifications {

    const val CHANNEL_ID = "refresh"

    /** Stable id: one notification, updated in place, removed with the foreground state. */
    const val NOTIFICATION_ID = 0x2EF7

    /**
     * The only two things this object needs from `NotificationManager`.
     *
     * It exists so the *decision* below ("create it if it is missing, then report whether it is
     * there") can be unit-tested: `NotificationManager` cannot be constructed in a JVM test without
     * Robolectric, and the half that can break is the decision, not the two binder calls. Same
     * trade-off as [WorkEnqueuer] on the scheduling side.
     */
    interface ChannelSink {

        /** `NotificationManager.getNotificationChannel(CHANNEL_ID) != null`. */
        fun channelExists(): Boolean

        /** `NotificationManager.createNotificationChannel(...)` with the 不打扰 settings. */
        fun createChannel(id: String, name: String, description: String)
    }

    /**
     * Make sure the `refresh` channel exists, and report whether a `refresh` notification may be
     * posted. `false` means "the platform would refuse the notification": the caller must then log the
     * failure and run **without** the foreground state, because an unpostable notification is a
     * process crash (`RemoteServiceException: Bad notification for startForeground`), not a warning.
     *
     * Below API 26 there are no channels, so a notification is valid without one — `true`.
     */
    fun ensureChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return ensureChannel(
            sink = AndroidChannelSink(manager),
            name = context.getString(R.string.refresh_channel_name),
            description = context.getString(R.string.refresh_channel_description),
        )
    }

    /**
     * The decision, free of `Context` and `NotificationManager`.
     *
     * `createChannel` is allowed to throw (an OEM build can refuse the channel): the failure is the
     * answer, not an exception to propagate — the re-read below decides, so the caller gets a plain
     * boolean to act on and the run never dies here.
     */
    internal fun ensureChannel(sink: ChannelSink, name: String, description: String): Boolean {
        if (!sink.channelExists()) {
            runCatching { sink.createChannel(CHANNEL_ID, name, description) }
        }
        return sink.channelExists()
    }

    /**
     * The production sink: the two `NotificationManager` calls this file is allowed to make.
     *
     * `@RequiresApi(O)` rather than an API check inside: the caller ([ensureChannel]) already
     * returned early on older platforms, and the annotation is what tells lint that the calls below
     * are unreachable on API 21–25.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private class AndroidChannelSink(private val manager: NotificationManager) : ChannelSink {

        override fun channelExists(): Boolean = manager.getNotificationChannel(CHANNEL_ID) != null

        override fun createChannel(id: String, name: String, description: String) {
            val channel = NotificationChannel(
                id,
                name,
                // LOW: a scheduled refresh is background housekeeping; it must never interrupt the TV.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                this.description = description
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** The `ForegroundInfo` WorkManager needs to put the worker in the foreground. */
    fun foregroundInfo(context: Context, content: RefreshNotificationContent): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            build(context, content),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

    fun build(context: Context, content: RefreshNotificationContent): Notification {
        val percent = content.percent
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(content.ongoing)
            // "可折叠": no actions, no expanded layout, nothing the user has to dismiss by hand.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
