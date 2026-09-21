package ilab.iptv.player.core.source.normalize

import ilab.iptv.player.core.model.ChannelGroup
import java.security.MessageDigest

/** Composite key mirroring `channel`'s unique index `UNIQUE(name_key, group_key)` (docs/02 §5.1). */
data class ChannelKey(val nameKey: String, val groupKey: String)

/** Stream identity inside one channel: `UNIQUE(channel_id, url_hash)` (docs/02 §5.1). */
data class StreamKey(val channelId: Long, val urlHash: String)

/**
 * The three derived keys of docs/02 §5.1, computed the same way in the parser's normalizer, the
 * Room upsert and the EPG matcher, so a channel deduped at parse time is the same row at persist
 * time.
 *
 * - `name_key` = [NameNormalizer.key] of the channel name.
 * - `group_key` = [NameNormalizer.key] of the group title; **a missing group falls back to
 *   [ChannelGroup.OTHER]'s key (`"other"`)**, because the column is `NOT NULL`. Deriving it from
 *   the group *title* (rather than the enum) is what makes the index do its job: docs/02 §5.1 says
 *   same-named channels may coexist across groups, which only works if two different groups keep
 *   two different keys. Treating every group as one of five enum values would merge them again.
 * - `url_hash` = SHA-256 (lower-case hex) of the **normalized** URL. It is a digest, not the URL,
 *   so the unique index can be built over an unbounded `TEXT` column; being a digest also means the
 *   index can never accidentally expose a token-bearing URL.
 */
object Keys {

    fun nameKey(name: String?): String = NameNormalizer.key(name)

    fun groupKey(groupTitle: String?): String =
        NameNormalizer.key(groupTitle).ifEmpty { ChannelGroup.OTHER.key }

    fun channelKey(name: String?, groupTitle: String?): ChannelKey =
        ChannelKey(nameKey(name), groupKey(groupTitle))

    /** URL hash over the [UrlNormalizer]-normalized form, so `HTTP://Host:80/a#x` == `http://host/a`. */
    fun urlHash(url: String?): String = sha256Hex(UrlNormalizer.normalize(url))

    fun streamKey(channelId: Long, url: String?): StreamKey = StreamKey(channelId, urlHash(url))

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
