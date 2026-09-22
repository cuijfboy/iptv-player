package ilab.iptv.player.feature.channels

import ilab.iptv.player.core.model.EpgChannelRef

/**
 * The manual-binding picker's search (P3-4). Pure so the "该 guide 无此频道" decision is unit-testable
 * without an Android dialog.
 *
 * The match is deliberately plain: a case-insensitive substring of the guide's display name **or** of
 * its id. It is a picker, not the matcher — the user is looking at the guide's own names, and being
 * clever here (folding, aliases, pinyin) would only hide rows the user can see in the list.
 */
object EpgPickerFilter {

    /**
     * The rows to show for [query]. A blank query shows everything; a query that matches nothing
     * returns an empty list, which is what the dialog turns into the honest "该 guide 无此频道" message.
     */
    fun match(candidates: List<EpgChannelRef>, query: String?): List<EpgChannelRef> {
        val needle = query?.trim().orEmpty()
        if (needle.isEmpty()) return candidates
        return candidates.filter {
            it.displayName.contains(needle, ignoreCase = true) ||
                it.id.contains(needle, ignoreCase = true)
        }
    }
}
