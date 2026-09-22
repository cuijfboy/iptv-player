package ilab.iptv.player.feature.channels.search

/** One searchable channel: what the browse list shows, minus the streams. */
data class SearchChannel(
    val channelId: Long,
    val name: String,
    val number: Int?,
    val groupKey: String,
)

/** One searchable programme: an EPG row inside the indexed window. */
data class SearchProgramme(
    val channelId: Long,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
)

/** What a search returned. Small [rank] = better; the screen only renders the order. */
sealed interface SearchHit {
    val channelId: Long
    val rank: Int
    val title: String
    val subtitle: String
    val channelName: String
}

data class ChannelHit(
    override val channelId: Long,
    override val rank: Int,
    val name: String,
    val number: Int?,
    val groupKey: String,
) : SearchHit {
    override val title: String get() = if (number != null) "$number $name" else name
    override val subtitle: String get() = "频道 · $groupKey"
    override val channelName: String get() = name
}

data class ProgrammeHit(
    override val channelId: Long,
    override val rank: Int,
    override val channelName: String,
    val programmeTitle: String,
    val startMs: Long,
    val stopMs: Long,
    /** True when the programme covers the moment the query was typed. */
    val live: Boolean,
) : SearchHit {
    override val title: String get() = programmeTitle
    override val subtitle: String
        get() = "节目 · $channelName" + if (live) " · 正在播出" else ""
}

/**
 * The P3-2 index (频道名 + 节目名) and its matching rule.
 *
 * STRUCTURE — why this shape answers inside 1 s: a **flat key table whose keys are filed under every
 * distinct character they contain**, not a trie. Each document contributes up to two keys — its
 * normalized text ([SearchText.normalize]) and its 拼音首字母 form ([PinyinInitials]) — and each key is
 * filed into the bucket of each of its characters. A query reads the single bucket of its own first
 * character (a key can only contain the query if it contains its first character, so the bucket is
 * exactly the candidate set) and scores the candidates with [SearchText.quality]
 * (0 exact / 1 prefix / 2 substring).
 *
 * Why not a prefix trie: the two shapes a 10-foot remote must answer are 数字 (channel numbers) and
 * CJK substrings ("新闻" inside "CCTV1 新闻综合"), and the second is not a prefix match — a trie would
 * have to index every suffix of every key. The flat table costs one pass over a single bucket, is
 * trivial to rebuild whenever the catalog or the guide changes, and its measured cost on the full
 * P2-7 corpus (658 频道 / 19 879 节目) is single-digit milliseconds per query — see the verification
 * record §4, which also states the point at which this structure would have to become a trie.
 *
 * WHAT IS DELIBERATELY NOT INDEXED: programmes outside the loaded window (P3-2 asks for the guide the
 * app has), hidden channels (they are hidden), and programme descriptions (the spec says 节目名).
 */
class SearchIndex private constructor(
    private val buckets: Map<Char, List<Entry>>,
    val channelCount: Int,
    val programmeCount: Int,
    val keyCount: Int,
) {

    private data class Entry(val key: String, val bonus: Int, val hit: SearchHit)

    /**
     * Hits for [query], best first.
     *
     * Tie-breaks, in order: match quality → key kind (a channel's own name before its 拼音首字母, a
     * channel before a programme) → "on air now" → channel number → programme start → id. The
     * "on air now" step is why [atMs] is a parameter: "新闻" at 19:05 should not put tomorrow's
     * 新闻联播 above the one the user is watching.
     */
    fun search(query: String, atMs: Long, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val q = SearchText.normalize(query)
        if (q.isEmpty()) return emptyList()
        val candidates = buckets[q.first()] ?: return emptyList()
        val best = HashMap<String, SearchHit>(candidates.size)
        candidates.forEach { entry ->
            val quality = SearchText.quality(entry.key, q) ?: return@forEach
            val rank = quality * QUALITY_STEP + entry.bonus
            val dedupe = dedupeKey(entry.hit)
            val previous = best[dedupe]
            if (previous == null || rank < previous.rank) best[dedupe] = entry.hit.withRank(rank)
        }
        return best.values
            .map { hit ->
                if (hit is ProgrammeHit) {
                    hit.copy(live = atMs >= hit.startMs && atMs < hit.stopMs)
                } else {
                    hit
                }
            }
            .sortedWith(HIT_ORDER)
            .take(limit)
    }

    private fun dedupeKey(hit: SearchHit): String = when (hit) {
        is ChannelHit -> "c:${hit.channelId}"
        is ProgrammeHit -> "p:${hit.channelId}:${hit.programmeTitle}"
    }

    private fun SearchHit.withRank(rank: Int): SearchHit = when (this) {
        is ChannelHit -> copy(rank = rank)
        is ProgrammeHit -> copy(rank = rank)
    }

    companion object {

        const val DEFAULT_LIMIT = 60

        /** One quality step is wider than every bonus, so "exact" always beats "prefix + bonus". */
        private const val QUALITY_STEP = 10

        private const val BONUS_CHANNEL_NAME = 0
        private const val BONUS_CHANNEL_NUMBER = 1
        private const val BONUS_CHANNEL_INITIALS = 2
        private const val BONUS_PROGRAMME_NAME = 4
        private const val BONUS_PROGRAMME_INITIALS = 6

        private val HIT_ORDER = compareBy<SearchHit>(
            { it.rank },
            { if (it is ProgrammeHit && it.live) 0 else 1 },
            { if (it is ChannelHit) it.number ?: Int.MAX_VALUE else Int.MAX_VALUE },
            { if (it is ProgrammeHit) it.startMs else 0L },
            { it.channelId },
            { it.title },
        )

        fun build(channels: List<SearchChannel>, programmes: List<SearchProgramme>): SearchIndex {
            val entries = mutableListOf<Entry>()
            channels.forEach { channel ->
                val nameKey = SearchText.normalize(channel.name)
                if (nameKey.isNotEmpty()) {
                    entries += Entry(nameKey, BONUS_CHANNEL_NAME, channelHit(channel, rank = 0))
                }
                val initialsKey = PinyinInitials.initialsOf(channel.name)
                if (initialsKey.isNotEmpty() && initialsKey != nameKey) {
                    entries += Entry(initialsKey, BONUS_CHANNEL_INITIALS, channelHit(channel, rank = 0))
                }
                channel.number?.let { number ->
                    entries += Entry(number.toString(), BONUS_CHANNEL_NUMBER, channelHit(channel, rank = 0))
                }
            }
            // One entry per (channel, title): a series repeats its title all evening, and the user
            // wants the channel, not thirty identical rows.
            val seenProgrammes = HashSet<String>()
            programmes.forEach { programme ->
                val dedupe = "${programme.channelId}:${SearchText.normalize(programme.title)}"
                if (!seenProgrammes.add(dedupe)) return@forEach
                val titleKey = SearchText.normalize(programme.title)
                if (titleKey.isNotEmpty()) {
                    entries += Entry(titleKey, BONUS_PROGRAMME_NAME, programmeHit(programme, rank = 0))
                }
                val initialsKey = PinyinInitials.initialsOf(programme.title)
                if (initialsKey.isNotEmpty() && initialsKey != titleKey) {
                    entries += Entry(
                        initialsKey,
                        BONUS_PROGRAMME_INITIALS,
                        programmeHit(programme, rank = 0),
                    )
                }
            }
            return SearchIndex(
                buckets = bucketize(entries),
                channelCount = channels.size,
                programmeCount = seenProgrammes.size,
                keyCount = entries.size,
            )
        }

        /**
         * Files every entry under each distinct character of its key.
         *
         * This is what makes "新闻" find 「CCTV1 新闻综合」 — a query whose first character is not the
         * key's first character is a substring match, and the bucket it reads is keyed on that
         * character. The dedupe below is a plain O(len²) scan because keys are short (≤ 20 chars) and
         * it avoids allocating a `Set` per key during a rebuild.
         */
        private fun bucketize(entries: List<Entry>): Map<Char, List<Entry>> {
            val buckets = HashMap<Char, MutableList<Entry>>()
            entries.forEach { entry ->
                val seen = CharArray(entry.key.length)
                var size = 0
                entry.key.forEach { ch ->
                    var known = false
                    for (index in 0 until size) if (seen[index] == ch) known = true
                    if (!known) {
                        seen[size++] = ch
                        buckets.getOrPut(ch) { mutableListOf() }.add(entry)
                    }
                }
            }
            return buckets
        }

        private fun channelHit(channel: SearchChannel, rank: Int): ChannelHit = ChannelHit(
            channelId = channel.channelId,
            rank = rank,
            name = channel.name,
            number = channel.number,
            groupKey = channel.groupKey,
        )

        private fun programmeHit(programme: SearchProgramme, rank: Int): ProgrammeHit = ProgrammeHit(
            channelId = programme.channelId,
            rank = rank,
            channelName = programme.channelName,
            programmeTitle = programme.title,
            startMs = programme.startMs,
            stopMs = programme.stopMs,
            // "On air now" depends on when the user types, so it is decided in search(), not here.
            live = false,
        )
    }
}
