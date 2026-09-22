package ilab.iptv.player.feature.player

/** Where the remote's focus is on the player screen. */
enum class PlayerFocusSlot {

    /** Focus is not on a view this screen manages (or the window has no focus yet). */
    NONE,

    /** The video root: the resting place, and the launch pad for LEFT/RIGHT into the bar. */
    ROOT,

    /** One of the info bar's four controls (画幅 / 音轨 / 字幕 / 过扫描). */
    INFO_BAR,

    /** The failure card's 重试 button. */
    RETRY,
}

/**
 * The player screen's focus rules, as a pure function (P3-7 item 2 "进入/退出弹层后焦点回到原来那项"
 * and item 4 "任何页面不得出现无焦点状态").
 *
 * Two rules are stated here instead of living as inline branches in the Activity:
 *
 * 1. **A retryable overlay takes focus.** §8.2 wants the failure card's 重试 entry reachable with one
 *    OK press, so when the card appears focus moves to it.
 * 2. **When that card goes away, focus must come back.** `View.GONE` cannot hold focus; leaving it
 *    there is the "dead zone" the card is about — the next key press would be handled by nothing, or
 *    by an arbitrary view picked by `focusSearch`. Before this rule the card's disappearance simply
 *    dropped focus wherever the framework put it.
 *
 * A bar control becoming focus-less is *not* forced back to the root here: while the bar is visible
 * the user is walking it with LEFT/RIGHT, and stealing focus between two of its controls would break
 * that walk. The bar's own teardown handles the case where it held focus
 * ([afterInfoBarHidden]).
 */
object PlayerFocusTarget {

    /** The slot that should hold focus after the overlay layer changed. */
    fun afterOverlayChange(
        overlay: PlayerOverlay,
        holding: PlayerFocusSlot,
    ): PlayerFocusSlot = when {
        // Rule 1: a failure with a retry entry is the thing the user must be able to act on.
        overlay.retryable -> PlayerFocusSlot.RETRY
        // Rule 2: the card is gone (NONE/STARTING/BUFFERING) and it had focus — put focus back.
        holding == PlayerFocusSlot.RETRY -> PlayerFocusSlot.ROOT
        // Otherwise the current holder is still a live view: do not move focus behind the user's back.
        else -> PlayerFocusSlot.NONE
    }

    /**
     * The slot after the info bar is taken down. `hideInfoBar` already had this rule for the bar's own
     * controls; the same `View.GONE` argument applies, so it is stated once with rule 2.
     */
    fun afterInfoBarHidden(holding: PlayerFocusSlot): PlayerFocusSlot =
        if (holding == PlayerFocusSlot.INFO_BAR) PlayerFocusSlot.ROOT else PlayerFocusSlot.NONE
}
