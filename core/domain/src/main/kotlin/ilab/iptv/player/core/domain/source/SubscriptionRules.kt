package ilab.iptv.player.core.domain.source

import ilab.iptv.player.core.model.SourceConfig
import ilab.iptv.player.core.model.SourceKind

/** Either the normalized URL that survived validation, or the reason it did not. */
sealed interface SubscriptionValidation {
    data class Accepted(val normalizedUrl: String, val label: String) : SubscriptionValidation

    /** [message] is shown to the user as-is. */
    data class Rejected(val message: String) : SubscriptionValidation
}

/**
 * The pure rules of P2-6's subscription management: validate a URL, normalize it, derive a stable
 * id and a default label, dedupe against the rows already in the table, and map the format hint onto
 * the frozen [SourceKind].
 *
 * Zero Android, zero I/O — the same functions back the settings screen, the repository that writes
 * the `source` table and the unit tests, so "what the form accepts" and "what the pipeline fetches"
 * cannot drift apart.
 */
object SubscriptionRules {

    /** The schemes the refresh pipeline can actually fetch (docs/02 §4.0 F2: `rtsp://` throws in OkHttp). */
    private val SUPPORTED_SCHEMES = setOf("http", "https")

    /**
     * Validates the form and returns the URL to store.
     *
     * Normalization mirrors the **Normalize** stage of docs/02 §6.1 for playlist URLs: lowercase the
     * scheme and host, drop the default port, drop the fragment, collapse the path's duplicate
     * slashes. Query strings are kept — several aggregates key their output on `?fmt=…`.
     *
     * [others] is what dedupe checks against: the same normalized URL on a **different** row is a
     * duplicate (two rows fetching one list would double every channel); [editingId] lets a row keep
     * its own URL while being edited.
     */
    fun validate(draft: SourceDraft, others: List<ManagedSource>, editingId: String? = null): SubscriptionValidation {
        val raw = draft.url.trim()
        if (raw.isEmpty()) return SubscriptionValidation.Rejected("请填写订阅地址")
        if (raw.any { it.isWhitespace() }) return SubscriptionValidation.Rejected("地址里有空格，请检查")

        val normalized = normalizeUrl(raw)
            ?: return SubscriptionValidation.Rejected("地址格式不正确，只支持 http/https：$raw")

        val clash = others.firstOrNull { it.id != editingId && normalizeUrl(it.url) == normalized }
        if (clash != null) {
            return SubscriptionValidation.Rejected("这个地址已经添加过：${clash.label}")
        }
        return SubscriptionValidation.Accepted(normalizedUrl = normalized, label = labelFor(draft.label, normalized))
    }

    /** Null when [raw] is not an http/https URL with a host; otherwise the normalized form. */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        val separator = trimmed.indexOf("://")
        if (separator <= 0) return null
        val scheme = trimmed.substring(0, separator).lowercase()
        if (scheme !in SUPPORTED_SCHEMES) return null

        val rest = trimmed.substring(separator + 3)
        val withoutFragment = rest.substringBefore('#')
        if (withoutFragment.isEmpty()) return null

        val authorityEnd = withoutFragment.indexOfFirst { it == '/' || it == '?' }
        val authority = if (authorityEnd < 0) withoutFragment else withoutFragment.substring(0, authorityEnd)
        val tail = if (authorityEnd < 0) "" else withoutFragment.substring(authorityEnd)
        if (authority.isEmpty()) return null
        // A host without a dot is accepted on purpose: a LAN portal is a real use case (docs/01 D3
        // "本机刷新"); what is rejected below is a bare ":" / ":8080" with no host at all.
        if (authority.startsWith(":") || authority.endsWith(":")) return null

        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        val port = authority.substringAfter(':', missingDelimiterValue = "")
        val defaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
        val normalizedAuthority = if (port.isEmpty() || defaultPort) host else "$host:$port"

        val path = tail.substringBefore('?')
            .replace(Regex("/{2,}"), "/")
            .ifEmpty { "/" }
        val query = tail.substringAfter('?', missingDelimiterValue = "")
        return buildString {
            append(scheme).append("://").append(normalizedAuthority).append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    /**
     * The row id for [normalizedUrl]. Derived from the URL so re-adding the same address is
     * idempotent, and stable across restarts (a 64-bit FNV-1a digest, hex-encoded).
     */
    fun idFor(normalizedUrl: String): String = "sub:" + fnv1a64(normalizedUrl).toString(16).padStart(16, '0')

    /** The label to store: what the user typed, else the URL's host (never blank). */
    fun labelFor(rawLabel: String, normalizedUrl: String): String {
        val trimmed = rawLabel.trim()
        if (trimmed.isNotEmpty()) return trimmed
        val withoutScheme = normalizedUrl.substringAfter("://")
        return withoutScheme.substringBefore('/')
    }

    /** docs/02 §4.2: `M3U`/`TXT` are the parser's dialects; the "auto" hint stores the common one. */
    fun kindFor(hint: PlaylistHint): SourceKind = when (hint) {
        PlaylistHint.AUTO, PlaylistHint.M3U -> SourceKind.M3U
        PlaylistHint.TXT -> SourceKind.TXT
    }

    /** The frozen §4.2 row for a validated draft. User rows are never `builtIn`. */
    fun toConfig(draft: SourceDraft, id: String, normalizedUrl: String): SourceConfig = SourceConfig(
        id = id,
        providerId = id,
        label = labelFor(draft.label, normalizedUrl),
        url = normalizedUrl,
        kind = kindFor(draft.hint),
        enabled = draft.enabled,
        builtIn = false,
    )

    private fun fnv1a64(value: String): Long {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037 as a signed Long
        val prime = 1099511628211L
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= prime
        }
        return hash
    }
}
