package ilab.iptv.player.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ilab.iptv.player.core.ui.player.PlayerContract

/**
 * The Android half of the playback notification: channel, ids, the two action `PendingIntent`s and the
 * `Notification` itself (P1-7 item 1).
 *
 * It paints [PlaybackNotificationContent] and decides nothing: the text, whether the control is
 * "play" or "pause", and whether the notification is ongoing all come from the pure builder, so the
 * decisions stay unit-tested instead of being buried in view code.
 *
 * The actions are service intents rather than media-button broadcasts: the service owns the engine,
 * so "pause" from the notification and "pause" from the remote end in the same place. (The remote's
 * `KEYCODE_MEDIA_*` arrives through the `MediaSession` instead — see [PlaybackService].)
 */
object PlaybackNotifications {

    const val CHANNEL_ID = "playback"

    /** Stable id: one notification, updated in place, removed when the session ends. */
    const val NOTIFICATION_ID = 0x91A7

    const val ACTION_TOGGLE = "ilab.iptv.player.action.PLAYBACK_TOGGLE"
    const val ACTION_STOP = "ilab.iptv.player.action.PLAYBACK_STOP"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.playback_channel_name),
            // LOW: playback state is visible on screen anyway, and a TV must not beep at the user
            // every time a channel starts.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.playback_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, content: PlaybackNotificationContent): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_playback_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(content.ongoing)
            // The notification is a state display for a TV: never alert, never vibrate, never re-sort.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                context.getString(
                    if (content.showPauseAction) R.string.playback_action_pause else R.string.playback_action_play,
                ),
                // One action code, two labels: the service resolves "toggle" against the live player
                // state, which is the same thing the main screen's MEDIA_PLAY/MEDIA_PAUSE keys do.
                serviceAction(context, ACTION_TOGGLE),
            )
            .addAction(0, context.getString(R.string.playback_action_stop), serviceAction(context, ACTION_STOP))
        content.channelId?.let { channelId ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN,
                    PlayerContract.intent(context, channelId),
                    // MUTABLE is required by the platform for an implicit intent from a notification,
                    // and the target is pinned to our own package by the contract.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun serviceAction(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_OPEN = 1
}
