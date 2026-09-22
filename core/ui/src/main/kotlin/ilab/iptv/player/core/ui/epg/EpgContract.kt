package ilab.iptv.player.core.ui.epg

import android.content.Context
import android.content.Intent

/**
 * The navigation contract for the P3-1 EPG grid, the same shape as [ilab.iptv.player.core.ui.player.PlayerContract]
 * and `SettingsContract`: an action string plus the extras, declared in `:core:ui` so no feature has to see
 * another feature's class (docs/02 §3.2 forbids feature → feature dependencies).
 *
 * `EXTRA_CHANNEL_ID` is the channel the caller wants the cursor on — the browse screen passes the row the
 * user was standing on. It is optional: without it the grid opens on the first channel at "now".
 */
object EpgContract {

    const val ACTION_SHOW_EPG = "ilab.iptv.player.action.SHOW_EPG"

    /** Optional: the channel whose row the cursor should land on. */
    const val EXTRA_CHANNEL_ID = "ilab.iptv.player.extra.EPG_CHANNEL_ID"

    fun intent(context: Context, channelId: Long? = null): Intent =
        Intent(ACTION_SHOW_EPG)
            .setPackage(context.packageName)
            .apply { if (channelId != null) putExtra(EXTRA_CHANNEL_ID, channelId) }

    fun readChannelId(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_CHANNEL_ID, MISSING_ID)?.takeIf { it != MISSING_ID }

    private const val MISSING_ID = Long.MIN_VALUE
}
