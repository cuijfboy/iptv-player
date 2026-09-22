package ilab.iptv.player.feature.settings.diag

import ilab.iptv.player.core.model.EpgBindingReport
import ilab.iptv.player.core.model.EpgBindingRow

/**
 * The **EPG 绑定与窗口** block of the diagnostics panel (BUG-20260922-018's read-only evidence entry).
 *
 * The QA round could see the aggregate coverage number and could see a blank grid row, but the build
 * had no way to join them: there was no "what is this channel bound to, and how many programmes does
 * that id hold inside the window the grid draws?" anywhere on the TV. This formatter renders exactly
 * that, one line per channel, so a tester can put the row and the number side by side.
 *
 * It is a pure function over an already-read [EpgBindingReport]: no repository, no clock, no Android.
 * The window and the read time arrive pre-formatted because the panel — not this file — owns the
 * device's locale and timezone, and keeping them out is what makes the block unit-testable.
 *
 * The five fields the card names appear literally, so the block can be read without a legend:
 * `channelId` is the `#<id>` prefix, `channelNo` / name ride along so a tester can find the row they
 * are looking at, `epgChannelId` is `id=`, `programmesInWindow` is `窗口内 N 条`, and `matchedBy` is
 * `依据=`; the window itself is the block's second line (`from` / `to` in epoch ms as well as local
 * time, so the evidence is unambiguous even if the TV's timezone differs from the reader's).
 */
object DiagEpgBinding {

    /** The block's title; the panel's toggle button uses the same words. */
    const val TITLE: String = "EPG 绑定与窗口（逐频道）"

    /**
     * One [DiagBlock] ready for the overview: what was read, over which window, and then every
     * channel. [windowLabel] and [readAt] are the caller's formatting of `report.window` and the
     * moment the report was taken (the numbers are a snapshot, not a stream).
     */
    fun block(report: EpgBindingReport, windowLabel: String, readAt: String): DiagBlock = DiagBlock(
        title = TITLE,
        facts = buildList {
            add(DiagFact("读取时刻", readAt))
            add(DiagFact("窗口", windowLabel))
            add(
                DiagFact(
                    "覆盖",
                    "匹配 ${report.matched} / 可看 ${report.withProgrammes} / " +
                        "空绑 ${report.emptyBinding} / 共 ${report.total}",
                ),
            )
            add(
                DiagFact(
                    "口径",
                    "可看 = 绑定 id 在窗口内有 ≥1 条节目；与 EPG_COVERAGE 同窗同刻，也就是网格能画出的行数",
                ),
            )
            add(DiagFact("逐频道", "格式：#channelId [频道号] 名称 | id=绑定 | 窗口内 N 条 | 依据=匹配层"))
            report.rows.forEach { row -> add(rowFact(row)) }
        },
    )

    /** One channel's line, as a labelled fact so the panel's `标签：值` renderer prints it verbatim. */
    fun rowFact(row: EpgBindingRow): DiagFact = DiagFact(label = row.label(), value = row.value())

    /** `#777 [777] CCTV-12社会与法`, with the hidden marker the grid suppresses. */
    private fun EpgBindingRow.label(): String = buildString {
        append('#').append(channelId)
        channelNo?.let { append(" [").append(it).append(']') }
        append(' ').append(channelName)
        if (hidden) append("（已隐藏）")
    }

    /**
     * `id=545944 窗口内 8 条 依据=NAME_EXACT`.
     *
     * An unbound channel says so in words instead of printing `null`: "no guide id at all" and "a
     * guide id that holds nothing these six hours" are the two different reasons a row is blank, and
     * telling them apart is the whole point of the entry.
     */
    private fun EpgBindingRow.value(): String = buildString {
        append("id=").append(epgChannelId ?: "(未绑定)")
        append(" 窗口内 ").append(programmesInWindow).append(" 条")
        append(" 依据=").append(matchedBy.name)
    }
}
