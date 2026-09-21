package ilab.iptv.player.core.source.parser

import ilab.iptv.player.core.model.SourceKind

/**
 * The two playlist dialects P1-1 supports (docs/04 P1-1). [label] is the value the
 * `SRC_PARSE_OK` event puts in its `m3u/txt` field (docs/03 §3.3).
 */
enum class PlaylistFormat(val label: String, val kind: SourceKind) {
    M3U("m3u", SourceKind.M3U),
    TXT("txt", SourceKind.TXT),
}
