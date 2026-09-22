package ilab.iptv.player.feature.channels.search

/** What one BACK press does on the search screen (P3-2's key contract). */
enum class SearchBackAction {

    /** Something is typed: clear it, stay on the screen (the "oops" case, P3-2's own rule). */
    CLEAR_QUERY,

    /** Nothing typed: hand BACK to the framework, which returns to the browse screen. */
    LEAVE_SCREEN,
}

/**
 * The search screen's BACK state machine (P3-7 item 1: every page's BACK behaviour is stated and
 * tested, not implied by an inline branch).
 *
 * The level is *the typed query*, not the focus zone: a user who has walked down into the results and
 * presses BACK wants the query gone, and after the clear the screen puts the cursor back on the
 * keypad (its own focus target does exactly that once the result list is empty).
 */
object SearchBackPolicy {

    fun decide(query: String): SearchBackAction =
        if (query.isEmpty()) SearchBackAction.LEAVE_SCREEN else SearchBackAction.CLEAR_QUERY
}
