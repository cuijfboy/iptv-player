package ilab.iptv.player.core.source.normalize

import ilab.iptv.player.core.model.RawEntry

/** One [RawEntry] plus its derived keys (docs/02 §5.1). */
data class NormalizedEntry(
    val entry: RawEntry,
    /** Readable name after width/whitespace normalization (becomes `channel.name`). */
    val name: String,
    val nameKey: String,
    /** Readable group after normalization; null when the source carried no group. */
    val groupTitle: String?,
    val groupKey: String,
    /** Normalized URL (docs/02 §6.1 Normalize stage). */
    val url: String,
    val urlHash: String,
) {
    val channelKey: ChannelKey get() = ChannelKey(nameKey, groupKey)
}

/** One accepted stream inside a [NormalizedChannel]; `urlHash` is the dedupe key. */
data class NormalizedStream(val entry: RawEntry, val url: String, val urlHash: String)

/**
 * Raw entries collapsed to the two identities of docs/02 §5.1: a channel is `(name_key, group_key)`
 * and a stream inside it is its `url_hash`. This is the shape `:core:data` can upsert directly, which
 * is the point: parse-time dedupe and the Room unique indexes must agree, or one source would insert
 * twice on the next refresh.
 */
data class NormalizedChannel(
    val nameKey: String,
    val groupKey: String,
    val name: String,
    val groupTitle: String?,
    val streams: List<NormalizedStream>,
    /** Every source that contributed an entry to this channel, in first-seen order. */
    val sourceIds: List<String>,
) {
    val channelKey: ChannelKey get() = ChannelKey(nameKey, groupKey)
}

/** `SRC_DEDUPE` fields (docs/03 §3.3): `raw`, `unique`, `perSource`. */
data class DedupeReport(val raw: Int, val unique: Int, val perSource: Map<String, Int>) {
    val removed: Int get() = raw - unique
}

/** Input and output of the Dedupe stage (docs/02 §6.1). */
data class DedupeResult(val entries: List<RawEntry>, val report: DedupeReport)

/** Everything the Normalize and Dedupe stages produce for one source refresh. */
data class NormalizedPlaylist(
    val channels: List<NormalizedChannel>,
    /** The stream-level, url_hash-deduped entries, in source-interleaved order. */
    val entries: List<NormalizedEntry>,
    val streamDedupe: DedupeReport,
    val channelDedupe: DedupeReport,
)
