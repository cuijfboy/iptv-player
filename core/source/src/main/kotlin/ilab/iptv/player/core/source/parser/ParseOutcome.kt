package ilab.iptv.player.core.source.parser

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.model.RawEntry

/**
 * What one parse produced, enough to emit `SRC_PARSE_OK` and to explain the rows that were dropped
 * (docs/03 §3.3 fields: `provider` / `m3u|txt` / `entries` / `skipped`).
 *
 * [skipped] is not an error count: it counts rows the parser refused to turn into an entry (a URL
 * with no `#EXTINF`, an `#EXTINF` with no URL, a one-field TXT line, an unparseable URL, a blank
 * name). A playlist whose rows are all malformed parses to an empty entry list plus a full
 * [skipped] count instead of failing, because the pipeline needs to know a source is junk without
 * crashing on it.
 */
data class ParseOutcome(
    val entries: List<RawEntry>,
    val format: PlaylistFormat,
    val lines: Int,
    val skipped: Int,
    val charset: String = "UTF-8",
    /** True when UTF-8 decoding failed and GB18030 was used (docs/03 §3.3 `NET_CHARSET_FALLBACK`). */
    val fallbackFromUtf8: Boolean = false,
    /** True when even the fallback charset had illegal bytes and replacements were substituted. */
    val lossyDecode: Boolean = false,
) {
    /** The success event code a caller logs for this outcome (docs/03 §3.3). */
    val eventCode: String get() = EventCodes.SRC_PARSE_OK

    /** The charset-fallback event code, or null when UTF-8 decoded cleanly. */
    val charsetEventCode: String? get() = if (fallbackFromUtf8) EventCodes.NET_CHARSET_FALLBACK else null

    /** `SRC_PARSE_OK` field: `provider` is the source id. */
    val formatLabel: String get() = format.label
}
