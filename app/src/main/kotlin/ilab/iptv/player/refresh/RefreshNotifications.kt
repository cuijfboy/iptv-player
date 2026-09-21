package ilab.iptv.player.refresh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
 */
object RefreshNotifications {

    const val CHANNEL_ID = "refresh"

    /** Stable id: one notification, updated in place, removed with the foreground state. */
    const val NOTIFICATION_ID = 0x2EF7

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.refresh_channel_name),
            // LOW: a scheduled refresh is background housekeeping; it must never interrupt the TV.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.refresh_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
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
