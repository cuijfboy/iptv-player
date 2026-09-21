package ilab.iptv.player.core.ui.player

import android.content.Context
import android.content.Intent

/**
 * The frozen navigation contract between the browse screen and the player screen (docs/02 §8.1
 * 导航契约): three extras in, one extra out.
 *
 * WHY THIS LIVES IN `:core:ui`: `:feature:channels` opens `PlayerActivity`, but features must not
 * depend on each other (docs/02 §3.2 — the matrix's `feature` column is `feature:*` itself). Both
 * features already declare `:core:ui`, so the shared contract, and the intent that carries it, sit
 * one layer below them. The player activity declares a matching intent filter, so neither module
 * ever needs the other's class on its compile path.
 *
 * The extras are the frozen ones — do not rename them: `:feature:player` and the browse screen are
 * written against this object, and the device proof (docs/05 §16) drives it with `am start -a`.
 */
object PlayerContract {

    /** Explicit action (no class reference) so `:feature:channels` can start the player screen. */
    const val ACTION_PLAY = "ilab.iptv.player.action.PLAY"

    /** docs/02 §8.1: required. */
    const val EXTRA_CHANNEL_ID = "ilab.iptv.player.extra.CHANNEL_ID"

    /** docs/02 §8.1: optional — the stream to start on; null means "first candidate". */
    const val EXTRA_STREAM_ID = "ilab.iptv.player.extra.STREAM_ID"

    /** docs/02 §8.1: optional, default true. */
    const val EXTRA_AUTOPLAY = "ilab.iptv.player.extra.AUTOPLAY"

    /** docs/02 §8.1: `RESULT_OK` carries the channel that was playing, so the list can restore focus. */
    const val EXTRA_RESULT_CHANNEL_ID = "ilab.iptv.player.extra.RESULT_CHANNEL_ID"

    /**
     * Builds the intent the browse screen hands to `startActivityForResult`.
     *
     * `setPackage` keeps the implicit intent inside our own package (no exported intent filter for
     * other apps to bind), and `EXTRA_AUTOPLAY` is always written so the receiving side has one
     * default instead of two.
     */
    fun intent(
        context: Context,
        channelId: Long,
        streamId: Long? = null,
        autoplay: Boolean = true,
    ): Intent = Intent(ACTION_PLAY)
        .setPackage(context.packageName)
        .putExtra(EXTRA_CHANNEL_ID, channelId)
        .putExtra(EXTRA_AUTOPLAY, autoplay)
        .apply { if (streamId != null) putExtra(EXTRA_STREAM_ID, streamId) }

    /** The result payload of the player screen (docs/02 §8.1: browser restores focus by channelId). */
    fun result(channelId: Long): Intent = Intent().putExtra(EXTRA_RESULT_CHANNEL_ID, channelId)

    /** Reads the frozen play input; returns null when [EXTRA_CHANNEL_ID] is missing (bad caller). */
    fun read(intent: Intent?): Input? {
        val channelId = intent?.getLongExtra(EXTRA_CHANNEL_ID, MISSING_ID) ?: return null
        if (channelId == MISSING_ID) return null
        return Input(
            channelId = channelId,
            streamId = intent.getLongExtra(EXTRA_STREAM_ID, MISSING_ID).takeIf { it != MISSING_ID },
            autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, true),
        )
    }

    data class Input(val channelId: Long, val streamId: Long?, val autoplay: Boolean)

    private const val MISSING_ID = Long.MIN_VALUE
}
