package ilab.iptv.player.feature.epg

/** What one BACK press does on the EPG grid screen (docs/02 §8.1 返回键层级). */
enum class EpgBackAction {

    /** The programme-detail layer is up: close it and keep the cursor where it was. */
    CLOSE_DETAIL,

    /** Nothing to close: hand BACK to the framework, which returns to the browse screen. */
    LEAVE_SCREEN,
}

/**
 * The EPG grid's BACK state machine (P3-7 item 1).
 *
 * The detail layer is an `AlertDialog` — a window of its own — so Android closes it before the
 * Activity ever sees BACK; this policy is what states that rule in one place, is what the audit table
 * in `docs/05-过程记录/45-P3-7焦点与返回键打磨.md` quotes, and is why the screen also restores focus
 * to the grid when the dialog closes (otherwise the follow-up key press has nowhere to go).
 */
object EpgBackPolicy {

    fun decide(detailOpen: Boolean): EpgBackAction =
        if (detailOpen) EpgBackAction.CLOSE_DETAIL else EpgBackAction.LEAVE_SCREEN
}
