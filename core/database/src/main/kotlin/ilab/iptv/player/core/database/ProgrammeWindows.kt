package ilab.iptv.player.core.database

import ilab.iptv.player.core.database.dao.ProgrammeDao

/** Half-open retention window for `programme`, in epoch milliseconds. */
data class KeepWindow(val fromMs: Long, val toMs: Long)

/**
 * The `programme` retention rule of docs/02 §5.1: a rolling window of `[now - 6 h, now + 48 h]`.
 * Seven days of EPG for 1k channels is 10–30 万 rows, so the table is trimmed on every EPG refresh
 * instead of growing forever; keeping 6 h of history is what makes "what was on just now" work in the
 * grid, and 48 h ahead covers the longest programme slot anyone scrolls to.
 */
object ProgrammeWindows {

    const val KEEP_PAST_MS: Long = 6 * 60 * 60 * 1000L
    const val KEEP_FUTURE_MS: Long = 48 * 60 * 60 * 1000L

    fun around(nowMs: Long): KeepWindow = KeepWindow(nowMs - KEEP_PAST_MS, nowMs + KEEP_FUTURE_MS)
}

/**
 * §5.1's pruning, per `epg_channel_id` batch. The id list is chunked so one refresh of 1k channels
 * does not build a single IN-list with 1k parameters (SQLite's default limit is 999 — a real ceiling,
 * not a style preference).
 */
suspend fun ProgrammeDao.pruneAround(
    nowMs: Long,
    epgChannelIds: List<String>,
    batchSize: Int = DEFAULT_PRUNE_BATCH,
): Int {
    val window = ProgrammeWindows.around(nowMs)
    var deleted = 0
    for (batch in epgChannelIds.chunked(batchSize.coerceAtLeast(1))) {
        deleted += pruneWindow(batch, window.fromMs, window.toMs)
    }
    return deleted
}

/** `SQLITE_MAX_VARIABLE_NUMBER` is 999 on the API levels this app supports; stay well under it. */
const val DEFAULT_PRUNE_BATCH: Int = 400
