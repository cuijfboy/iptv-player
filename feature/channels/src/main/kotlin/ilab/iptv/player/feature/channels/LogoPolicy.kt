package ilab.iptv.player.feature.channels

/**
 * P2-3 logo rules (docs/04 P2-3: "Coil + 磁盘缓存 + 占位；无网络不崩"). Pure Kotlin, no Android,
 * so the two things a reviewer actually wants pinned — *which* URLs are attempted and what the cache
 * is keyed on — are unit-testable without a device or a network.
 *
 * Two decisions, both deliberately here instead of buried in a binder:
 * - **Which URL is attempted.** A blank or non-`http(s)` logo (a relative path, a `file://`, a typo
 *   like `htp://`) is *not* attempted: it returns null and the row shows the placeholder. The
 *   alternative — handing Coil a string it cannot fetch — costs a request and a guaranteed failure
 *   event for every such row, on every bind.
 * - **What the cache is keyed on.** The URL with its fragment removed. A fragment never changes the
 *   bytes served (it is a client-side anchor) but *does* change a raw URL's string, so keying on the
 *   raw string would fetch and store the same image twice. Coil defaults to the data's own string as
 *   its memory+disk key, so the key is passed explicitly (see the adapter).
 */
object LogoPolicy {

    /** Scheme prefixes a logo may use. Anything else (blank, `file:`, relative) is not loadable. */
    private val allowedSchemes = listOf("http://", "https://")

    /**
     * The URL to hand Coil, or null when there is nothing worth loading. Case-insensitive on the
     * scheme (playlists do ship `HTTP://`), and the original string is returned otherwise so the
     * request is byte-for-byte what the playlist declared.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val scheme = trimmed.lowercase()
        if (allowedSchemes.none { scheme.startsWith(it) }) return null
        // "http://" alone (no host) is not a fetchable URL; require something after the scheme.
        val afterScheme = trimmed.substringAfter("://").substringBefore('/').substringBefore('?')
        if (afterScheme.isEmpty()) return null
        return trimmed
    }

    /** The memory + disk cache key for [url]: the URL without its fragment (never changes the bytes). */
    fun cacheKey(url: String): String {
        val trimmed = url.trim()
        val hash = trimmed.indexOf('#')
        return if (hash >= 0) trimmed.substring(0, hash) else trimmed
    }
}
