package ilab.iptv.player.core.source.normalize

import ilab.iptv.player.core.model.RawEntry

/**
 * The Normalize + Dedupe stages of the refresh pipeline (docs/02 §6.1).
 *
 * Dedupe happens at two levels because docs/02 §5.1 has two unique indexes and they mean different
 * things. **Stream level** is `UNIQUE(channel_id, url_hash)`; the pipeline additionally drops the
 * same URL seen again at all (`SRC_DEDUPE`), interleaving sources so one big provider cannot crowd
 * out the rest. One URL may still serve several channels, so this caps redundant probing rather than
 * enforcing "one URL per database". **Channel level** is `UNIQUE(name_key, group_key)`: same-named
 * channels in different groups stay separate, which is what the index was changed to allow.
 *
 * Everything here is pure and order-preserving, so tests can pin the exact result.
 */
object PlaylistNormalizer {

    /** Normalizes names/urls and derives the docs/02 §5.1 keys; order is preserved. */
    fun normalizeEntries(entries: List<RawEntry>): List<NormalizedEntry> = entries.map { entry ->
        NormalizedEntry(
            entry = entry,
            name = NameNormalizer.display(entry.name),
            nameKey = NameNormalizer.key(entry.name),
            groupTitle = NameNormalizer.display(entry.groupTitle).ifBlank { null },
            groupKey = Keys.groupKey(entry.groupTitle),
            url = UrlNormalizer.normalize(entry.url),
            urlHash = Keys.urlHash(entry.url),
        )
    }

    /**
     * Drops entries whose normalized URL was already seen. With [interleaveBySource] (the default,
     * docs/02 §6.1 "按源交错保留多样性") the input is re-read round-robin across sources, so the kept
     * set is spread over providers instead of being "whichever source came first".
     */
    fun dedupeByUrlHash(entries: List<RawEntry>, interleaveBySource: Boolean = true): DedupeResult {
        val ordered = if (interleaveBySource) interleave(entries) else entries
        val seen = HashSet<String>()
        val kept = ArrayList<RawEntry>(ordered.size)
        val perSource = LinkedHashMap<String, Int>()
        for (entry in ordered) {
            val hash = Keys.urlHash(entry.url)
            if (!seen.add(hash)) continue
            kept += entry
            perSource[entry.sourceId] = (perSource[entry.sourceId] ?: 0) + 1
        }
        return DedupeResult(kept, DedupeReport(raw = entries.size, unique = kept.size, perSource = perSource))
    }

    /** Groups normalized entries by `(name_key, group_key)` and dedupes streams by `url_hash`. */
    fun groupChannels(entries: List<NormalizedEntry>): List<NormalizedChannel> {
        val byChannel = LinkedHashMap<ChannelKey, MutableChannel>()
        for (entry in entries) {
            val channel = byChannel.getOrPut(entry.channelKey) { MutableChannel(entry) }
            channel.addIfNew(entry)
        }
        return byChannel.values.map { it.build() }
    }

    /** Runs the whole Normalize + Dedupe stage for one batch of parsed entries. */
    fun normalize(entries: List<RawEntry>, interleaveBySource: Boolean = true): NormalizedPlaylist {
        val deduped = dedupeByUrlHash(entries, interleaveBySource)
        // Dedupe decides WHICH raw entries survive; normalization decides what they look like. Doing
        // it in this order keeps the interleaved order that dedupe chose.
        val normalized = normalizeEntries(entries)
        val dedupedNormalized = normalizeEntries(deduped.entries)
        val channels = groupChannels(dedupedNormalized)

        val channelsPerSource = LinkedHashMap<String, Int>()
        for (channel in channels) {
            for (sourceId in channel.sourceIds) {
                channelsPerSource[sourceId] = (channelsPerSource[sourceId] ?: 0) + 1
            }
        }
        return NormalizedPlaylist(
            channels = channels,
            entries = dedupedNormalized,
            streamDedupe = deduped.report,
            channelDedupe = DedupeReport(
                raw = normalized.size,
                unique = channels.size,
                perSource = channelsPerSource,
            ),
        )
    }

    /**
     * Round-robin merge across sources, preserving each source's internal order. The first
     * appearance of a source fixes its slot, so the result is deterministic for a given input.
     */
    private fun interleave(entries: List<RawEntry>): List<RawEntry> {
        if (entries.isEmpty()) return entries
        val bySource = LinkedHashMap<String, MutableList<RawEntry>>()
        for (entry in entries) bySource.getOrPut(entry.sourceId) { ArrayList() } += entry
        if (bySource.size == 1) return entries

        val sources = bySource.entries.toList()
        val cursor = IntArray(sources.size)
        val merged = ArrayList<RawEntry>(entries.size)
        var exhausted = 0
        while (exhausted < sources.size) {
            for (i in sources.indices) {
                val index = cursor[i]
                if (index >= sources[i].value.size) continue
                merged += sources[i].value[index]
                cursor[i] = index + 1
                if (cursor[i] >= sources[i].value.size) exhausted++
            }
        }
        return merged
    }

    private class MutableChannel(first: NormalizedEntry) {
        val nameKey = first.nameKey
        val groupKey = first.groupKey
        val name = first.name
        val groupTitle = first.groupTitle
        val sourceIds = LinkedHashSet<String>()
        val streams = ArrayList<NormalizedStream>()
        private val seenHashes = HashSet<String>()

        fun addIfNew(entry: NormalizedEntry) {
            sourceIds += entry.entry.sourceId
            if (seenHashes.add(entry.urlHash)) {
                streams += NormalizedStream(entry.entry, entry.url, entry.urlHash)
            }
        }

        fun build() = NormalizedChannel(
            nameKey = nameKey,
            groupKey = groupKey,
            name = name,
            groupTitle = groupTitle,
            streams = streams,
            sourceIds = sourceIds.toList(),
        )
    }
}
