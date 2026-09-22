package ilab.iptv.player.feature.channels

import ilab.iptv.player.core.ui.back.BackHierarchy
import ilab.iptv.player.core.ui.back.TvScreen

/** What one BACK press does on the browse screen (docs/02 §8.1 返回键层级). */
enum class BrowseBackAction {

    /** Manage mode is on: leave the mode, stay on the list (P3-4's in-page level). */
    EXIT_MANAGE_MODE,

    /** Nothing left to close: hand BACK to the framework, which leaves the browse screen. */
    LEAVE_SCREEN,
}

/**
 * The browse screen's BACK state machine, extracted from the Activity so the rule is unit-tested
 * instead of being an inline `if` that a later edit can invert (P3-7 item 5).
 *
 * The screen has exactly one in-page level: P3-4's manage mode. Dialogs are separate windows and
 * consume BACK themselves (documented in the audit table of
 * `docs/05-过程记录/45-P3-7焦点与返回键打磨.md`), so they never reach this policy.
 */
object BrowseBackPolicy {

    /** The screen this policy governs; the parent is [BackHierarchy]'s business, not this one's. */
    val SCREEN: TvScreen = TvScreen.BROWSE

    fun decide(manageModeActive: Boolean): BrowseBackAction =
        if (manageModeActive) BrowseBackAction.EXIT_MANAGE_MODE else BrowseBackAction.LEAVE_SCREEN
}
