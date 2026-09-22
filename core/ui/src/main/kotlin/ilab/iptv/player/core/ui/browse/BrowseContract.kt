package ilab.iptv.player.core.ui.browse

import android.content.Context
import android.content.Intent

/**
 * The navigation contract for the browse (channel list) screen, the same shape as
 * [ilab.iptv.player.core.ui.player.PlayerContract] and `SettingsContract`: an action plus its extras
 * in `:core:ui`, so no feature module has to see another feature's class (docs/02 §3.2).
 *
 * Two callers use it:
 * - the launcher (`:app`'s `MainActivity`) sends a returning user straight to the list;
 * - the P2-9 wizard's 开看 step sends a first-run user to the list **with a hint**, because the
 *   point of step ③ is not only "the list opens" but "the remote is already on a channel that plays"
 *   — that promise has to travel with the intent.
 */
object BrowseContract {

    const val ACTION_BROWSE = "ilab.iptv.player.action.BROWSE"

    /** Optional hint for the screen; null means "no particular instruction". */
    const val EXTRA_HINT = "ilab.iptv.player.extra.BROWSE_HINT"

    /**
     * "Put the remote on the first playable channel and say 按 OK 播放." The 开看 step's promise,
     * carried as data so the browse screen does not need to know who called it or why.
     */
    const val HINT_FIRST_PLAY = "first-play"

    /** What the browse screen does when it opens from the wizard. */
    enum class Hint { FIRST_PLAY }

    fun intent(context: Context, hint: Hint? = null): Intent =
        Intent(ACTION_BROWSE)
            .setPackage(context.packageName)
            .apply { if (hint != null) putExtra(EXTRA_HINT, hintValue(hint)) }

    /** Reads [EXTRA_HINT]; an unknown value is treated as "no hint" rather than as a crash. */
    fun readHint(intent: Intent?): Hint? =
        when (intent?.getStringExtra(EXTRA_HINT)) {
            HINT_FIRST_PLAY -> Hint.FIRST_PLAY
            else -> null
        }

    private fun hintValue(hint: Hint): String = when (hint) {
        Hint.FIRST_PLAY -> HINT_FIRST_PLAY
    }
}
