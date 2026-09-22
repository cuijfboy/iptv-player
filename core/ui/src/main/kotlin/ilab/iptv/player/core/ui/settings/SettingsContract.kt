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

    /**
     * P2-8: the settings page itself (docs/02 §8.1's `BrowseActivity → SettingsActivity`). The browse
     * screen opens it with this action; the source-management page below is one of its entries.
     */
    const val ACTION_SETTINGS = "ilab.iptv.player.action.SETTINGS"

    /** Explicit action (no class reference) so the browse screen can open source management. */
    const val ACTION_SOURCE_MANAGEMENT = "ilab.iptv.player.action.SOURCE_MANAGEMENT"

    /** `setPackage` keeps the implicit intent inside our own package. */
    fun settingsIntent(context: Context): Intent =
        Intent(ACTION_SETTINGS).setPackage(context.packageName)

    /** `setPackage` keeps the implicit intent inside our own package (no exported filter needed). */
    fun sourceManagementIntent(context: Context): Intent =
        Intent(ACTION_SOURCE_MANAGEMENT).setPackage(context.packageName)
}
