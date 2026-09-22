package ilab.iptv.player.core.model

/**
 * One channel's EPG binding, read back the way the grid sees it — the read-only evidence entry the
 * QA round could not find in the build (BUG-20260922-018).
 *
 * The bug's symptom was a grid row that stayed blank while the coverage event claimed the channel was
 * covered. That question can only be answered per channel, and it needs four facts at once:
 *
 * - **which** guide channel id the row is bound to ([epgChannelId]); nothing bound and bound-to-a-blank
 *   id are different answers, so the null case is kept as null rather than as an empty string;
 * - **how many** programmes that id holds inside [EpgBindingReport.window] ([programmesInWindow]) —
 *   the count is the grid window's, so "N > 0" and "the row has a block" are the same statement;
 * - **why** the row is bound to it ([matchedBy] — the match tier, `NONE` when nothing bound it);
 * - the channel itself ([channelId] / [channelNo] / [channelName]), because a tester looks a row up by
 *   the number on screen.
 *
 * [channelId] is the business channel id (`channel.id`), never the guide id: the two are different
 * identifiers and mixing them is the identity hop this model exists to make readable.
 */
data class EpgBindingRow(
    val channelId: Long,
    val channelName: String,
    val channelNo: Int?,
    val epgChannelId: String?,
    val matchedBy: EpgMatchType,
    val programmesInWindow: Int,
    /**
     * `channel.hidden`. A hidden channel is a legal row to ask about even though the grid does not
     * draw it — the QA round hit exactly that (CCTV1 was hidden during the run and could not be
     * observed), so the report marks it instead of silently omitting it.
     */
    val hidden: Boolean = false,
) {

    /** Bound at all, whether or not the guide holds anything for it. */
    val bound: Boolean get() = !epgChannelId.isNullOrBlank()

    /** Bound to a guide id with nothing inside the window — the "blank row" shape EPG-BIND named. */
    val emptyBinding: Boolean get() = bound && programmesInWindow <= 0
}

/**
 * Every channel's binding plus the window the counts were taken over, so a reader can tell what "0"
 * means (nothing in *these* hours) instead of assuming it means "no guide at all".
 *
 * The three aggregates mirror `EpgCoverage` on purpose and are computed from [rows] rather than read
 * from the channel table: the whole point of this report is that it can be compared, row by row,
 * against the coverage number the run logged (`matched` / `withProgrammes` / `emptyBinding`).
 */
data class EpgBindingReport(
    val window: EpgTimeWindow,
    val rows: List<EpgBindingRow>,
) {

    val total: Int get() = rows.size

    val matched: Int get() = rows.count { it.bound }

    val withProgrammes: Int get() = rows.count { it.bound && it.programmesInWindow > 0 }

    val emptyBinding: Int get() = matched - withProgrammes
}
