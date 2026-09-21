package ilab.iptv.player.core.source.parser

import ilab.iptv.player.core.model.RawEntry
import java.util.Locale

/**
 * M3U playlist parser (the `.m3u8` *media* playlist is a player concern; this reads the list file
 * format). Implements docs/04 P1-1:
 *
 * - `#EXTM3U` header, with or without attributes;
 * - `#EXTINF:<duration> <attrs>,<title>` where the attributes `tvg-id`, `tvg-name`, `tvg-chno`,
 *   `tvg-logo`, `group-title` may be quoted or bare, in any order, and any of them may be missing;
 * - `#EXTGRP:<group>` as the group for the following entry (an `#EXTINF`'s own `group-title` wins);
 * - `#EXTVLCOPT:<key>=<value>` — tolerated for any key, and the two with a home in [RawEntry]
 *   (`http-user-agent`, `http-referrer`) are captured;
 * - a URL line belongs to the `#EXTINF` directly above it.
 *
 * Never throws on malformed input: bad rows are counted in [ParseOutcome.skipped], per docs/04 P1-1
 * ("解析器不许抛未捕获异常"). Attribute scanning is one linear pass with no regex over the line, so
 * a multi-megabyte URL line cannot blow up in backtracking.
 */
object M3uParser {

    private const val EXTINF = "#EXTINF:"
    private const val EXTGRP = "#EXTGRP:"
    private const val EXTVLCOPT = "#EXTVLCOPT:"
    private const val HEADER = "#EXTM3U"

    fun parse(text: String, sourceId: String): ParseOutcome {
        val entries = ArrayList<RawEntry>()
        var lines = 0
        var skipped = 0
        var pending: Pending? = null
        var pendingGroup: String? = null

        for (rawLine in text.lineSequence()) {
            lines++
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                when {
                    line.regionMatches(0, EXTINF, 0, EXTINF.length, ignoreCase = true) -> {
                        // Two #EXTINF in a row: the first never got its URL.
                        if (pending != null) skipped++
                        pending = parseExtInf(line.substring(EXTINF.length), pendingGroup)
                        pendingGroup = null
                    }

                    line.regionMatches(0, EXTGRP, 0, EXTGRP.length, ignoreCase = true) -> {
                        val group = line.substring(EXTGRP.length).trim()
                        val current = pending
                        if (current != null) {
                            if (current.groupTitle.isNullOrBlank()) current.groupTitle = group.ifBlank { null }
                        } else {
                            pendingGroup = group.ifBlank { null }
                        }
                    }

                    line.regionMatches(0, EXTVLCOPT, 0, EXTVLCOPT.length, ignoreCase = true) ->
                        applyVlcOpt(pending, line.substring(EXTVLCOPT.length).trim())

                    // #EXTM3U and every other directive: ignored, but tolerated.
                    else -> Unit
                }
                continue
            }

            val current = pending
            if (current == null) {
                skipped++
                continue
            }
            pending = null
            pendingGroup = null

            val url = Urls.extract(line)
            if (!Urls.isValid(url)) {
                skipped++
                continue
            }
            val name = current.name.trim().ifBlank { current.tvgName?.trim().orEmpty() }
            if (name.isBlank()) {
                skipped++
                continue
            }
            entries += RawEntry(
                name = name,
                url = url,
                tvgId = current.tvgId?.takeIf { it.isNotBlank() },
                tvgName = current.tvgName?.takeIf { it.isNotBlank() },
                groupTitle = current.groupTitle?.trim()?.takeIf { it.isNotBlank() },
                logo = current.logo?.takeIf { it.isNotBlank() },
                channelNo = current.channelNo,
                userAgent = current.userAgent?.takeIf { it.isNotBlank() },
                referrer = current.referrer?.takeIf { it.isNotBlank() },
                sourceId = sourceId,
            )
        }

        // A trailing #EXTINF with no URL never became an entry.
        if (pending != null) skipped++
        return ParseOutcome(entries, PlaylistFormat.M3U, lines, skipped)
    }

    /** True when the text starts with the `#EXTM3U` header (after optional blank lines). */
    fun hasHeader(text: String): Boolean =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.regionMatches(0, HEADER, 0, HEADER.length, ignoreCase = true) == true

    private class Pending(
        val name: String,
        val tvgId: String?,
        val tvgName: String?,
        val logo: String?,
        val channelNo: Int?,
        var groupTitle: String?,
        var userAgent: String? = null,
        var referrer: String? = null,
    )

    private fun parseExtInf(body: String, extgrp: String?): Pending {
        val split = splitAtTitleComma(body)
        val header = body.substring(0, split)
        val title = body.substring(split).trimStart(',').trim()

        val attrs = parseAttributes(header)
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extgrp
        return Pending(
            name = title,
            tvgId = attrs["tvg-id"],
            tvgName = attrs["tvg-name"],
            logo = attrs["tvg-logo"],
            channelNo = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
            groupTitle = group,
        )
    }

    /**
     * Index of the comma separating `#EXTINF` attributes from the title, or `body.length` when there
     * is none. Commas inside a quoted attribute (`group-title="新闻,财经"`) do not count.
     */
    private fun splitAtTitleComma(body: String): Int {
        var quote: Char? = null
        for (i in body.indices) {
            val c = body[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == ',' -> return i
            }
        }
        return body.length
    }

    /**
     * One linear pass over the attribute section. A token without `=` (the leading duration, e.g.
     * `-1`) is skipped; the first occurrence of a key wins; keys are case-insensitive.
     */
    private fun parseAttributes(section: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < section.length) {
            while (i < section.length && section[i].isWhitespace()) i++
            val keyStart = i
            while (i < section.length && section[i] != '=' && !section[i].isWhitespace()) i++
            if (i >= section.length) break
            if (section[i] != '=') continue // duration token, or junk between attributes

            val key = section.substring(keyStart, i).trim().lowercase(Locale.ROOT)
            i++ // '='
            while (i < section.length && section[i].isWhitespace()) i++

            val value: String
            if (i < section.length && (section[i] == '"' || section[i] == '\'')) {
                val quote = section[i]
                i++
                val valueStart = i
                while (i < section.length && section[i] != quote) i++
                value = section.substring(valueStart, i)
                if (i < section.length) i++ // closing quote
            } else {
                val valueStart = i
                while (i < section.length && !section[i].isWhitespace()) i++
                value = section.substring(valueStart, i)
            }
            // First occurrence wins. Written as an explicit check because Map.putIfAbsent is API 24
            // on Android and this module's minSdk is 21 (lint NewApi would fail the build).
            if (key.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        return out
    }

    private fun applyVlcOpt(pending: Pending?, opt: String) {
        if (pending == null) return
        val eq = opt.indexOf('=')
        if (eq <= 0) return
        val key = opt.substring(0, eq).trim().lowercase(Locale.ROOT)
        val value = opt.substring(eq + 1).trim()
        when (key) {
            "http-user-agent" -> pending.userAgent = value
            "http-referrer" -> pending.referrer = value
            else -> Unit // #EXTVLCOPT is tolerated for any key; only the two above map to RawEntry
        }
    }
}
