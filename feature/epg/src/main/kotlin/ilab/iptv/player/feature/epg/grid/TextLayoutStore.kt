package ilab.iptv.player.feature.epg.grid

/**
 * The text layout cache docs/02 §8.3 fixes at **512 entries maximum** ("StaticLayout results cached by
 * text, width and style"). Layout allocation is the visible cost of a Canvas grid — the S3 prototype's
 * +9.5 MB of PSS was this cache — so its hit rate is a reported number (`layoutCacheHit` in
 * `PERF_EPG_GRID`).
 *
 * Policy choice, recorded in the P3-1 report: the prototype stopped inserting once the cache filled,
 * which makes the miss rate climb for the rest of the session. Here the cache is a bounded LRU with the
 * same 512-entry ceiling, so a block that leaves the screen and comes back is a hit again.
 */

enum class GridTextStyle { BLOCK_TITLE, BLOCK_TIME, RULER, RULER_DAY, CHANNEL, PLACEHOLDER }

/** Cache key: the same text at a different pixel width is a different layout. */
data class TextKey(val text: String, val widthPx: Int, val style: GridTextStyle)

/** A laid-out string. The Android side wraps a `StaticLayout`; tests and the benchmark use a stub. */
interface TextHandle {
    val key: TextKey

    /** Height of the laid-out text in pixels — the renderer centres the channel column with it. */
    val heightPx: Float
}

/** Produces a handle on a miss. The only Android-aware half of the cache. */
fun interface TextLayoutFactory {
    fun create(key: TextKey): TextHandle
}

class TextLayoutStore(
    private val factory: TextLayoutFactory,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    /** Access-ordered map plus `removeEldestEntry` is a plain LRU, and the map is the bound. */
    private val cache = object : LinkedHashMap<TextKey, TextHandle>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, TextHandle>): Boolean =
            size > maxEntries
    }

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    val size: Int get() = cache.size

    fun get(text: String, widthPx: Int, style: GridTextStyle): TextHandle {
        val key = TextKey(text, widthPx, style)
        cache[key]?.let {
            hits++
            return it
        }
        misses++
        val created = factory.create(key)
        cache[key] = created
        return created
    }

    /** 1.0 before anything was asked: "no misses yet" reports as perfect, not as a division by zero. */
    fun hitRate(): Double {
        val total = hits + misses
        return if (total == 0) 1.0 else hits.toDouble() / total
    }

    fun resetCounters() {
        hits = 0
        misses = 0
    }

    companion object {
        /** docs/02 §8.3, frozen. */
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
