package ilab.iptv.player.feature.channels

/**
 * Which list row takes focus when the browse screen comes back to the foreground (P3-7 item 2:
 * "进入/退出弹层后焦点回到原来那项", and item 4: no screen may end up focus-less).
 *
 * Two callers need the same answer and used to compute it separately:
 *
 * - the player result (`docs/02 §8.1 播放返回`: focus the channel that was playing, else the group's
 *   first row), and
 * - `onResume`, where returning from the EPG grid / search / settings can leave the list without
 *   focus if the catalog re-emitted while the screen was in the background.
 *
 * Pure over [ChannelListRow] so the fallback chain is pinned by a test rather than by watching the
 * list: preferred channel → first channel row → -1 (empty list, caller does nothing).
 */
object ChannelFocusTarget {

    /** No focusable row exists (e.g. the list is still empty); the caller must not call focus APIs. */
    const val NO_ROW: Int = -1

    fun positionOf(rows: List<ChannelListRow>, preferredChannelId: Long?): Int {
        if (preferredChannelId != null) {
            val index = rows.indexOfFirst {
                it is ChannelListRow.ChannelItem && it.channelId == preferredChannelId
            }
            if (index >= 0) return index
        }
        // "找不到则回到分组首项" (docs/02 §8.1): group headers are not focusable, so the first
        // *channel* row is the first thing the remote can actually land on.
        return rows.indexOfFirst { it is ChannelListRow.ChannelItem }
    }
}
