package ilab.iptv.player.core.ui.settings

import android.content.Context
import android.content.Intent

/**
 * The navigation contract for the settings screens (docs/02 §8.1: `BrowseActivity → SettingsActivity
 * → DiagnosticsFragment / LogViewFragment`; P2-6 adds the source-management page next to them).
 *
 * Same reason as [ilab.iptv.player.core.ui.player.PlayerContract]: `:feature:channels` must be able to
 * open a screen that lives in `:feature:settings`, and `docs/02 §3.2` forbids feature → feature
 * dependencies. Both modules already declare `:core:ui`, so the action lives one layer below them and
 * the settings module declares a matching intent filter.
 */
object SettingsContract {

    /** Explicit action (no class reference) so the browse screen can open source management. */
    const val ACTION_SOURCE_MANAGEMENT = "ilab.iptv.player.action.SOURCE_MANAGEMENT"

    /** `setPackage` keeps the implicit intent inside our own package (no exported filter needed). */
    fun sourceManagementIntent(context: Context): Intent =
        Intent(ACTION_SOURCE_MANAGEMENT).setPackage(context.packageName)
}
