package ilab.iptv.player.feature.channels

/**
 * "The first channel that can actually play" — the landing spot the P2-9 wizard's 开看 step promises
 * ("进入频道列表并聚焦第一个可播放频道").
 *
 * A pure function rather than a search inlined in the activity, because the definition is the
 * interesting part and it is easy to get subtly wrong:
 * - a **group header** is not a channel and is skipped;
 * - a channel with **zero streams** is not playable — the refresh keeps such a channel on purpose
 *   ("nothing is silently dropped", docs/01 F2 `某源全部候选不可用`), so the first row of a fresh or
 *   partly-failed refresh can be one of them, and landing on it would make the wizard's promise
 *   false on the very first press of OK;
 * - hidden channels are already excluded by the list's own filter (the browse screen observes with
 *   `includeHidden = false`), so this function does not re-check that.
 *
 * `-1` means "nothing playable": the caller then leaves the remote where the list put it, which is
 * the same fallback docs/02 §8.1 defines for a channel that is no longer in the list.
 */
object FirstPlayableRow {

    /** Index of the first playable row in [rows], or -1 when there is none. */
    fun indexOf(rows: List<ChannelListRow>): Int = rows.indexOfFirst { row ->
        row is ChannelListRow.ChannelItem && row.streamCount > 0
    }
}
