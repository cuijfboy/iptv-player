package ilab.iptv.player.core.source.parser

import ilab.iptv.player.core.model.RawEntry

/**
 * Plain-text playlist parser (docs/04 P1-1, docs/02 §6.1 "TXT（`#genre#`、`名,url`）").
 *
 * Accepted rows:
 * - `名称,URL` — two fields;
 * - `名称,URL,分组` — three or more fields; everything after the second comma is the group, so a
 *   group that itself contains a comma survives (`名称,URL,新闻,财经` → group `新闻,财经`);
 * - `分组,#genre#` — a section marker: every following row without its own group belongs to it.
 *   A bare `#genre#` clears the current group.
 *
 * Group ownership rule (asked for explicitly in the P1-1 brief): an explicit third field wins;
 * otherwise the most recent `#genre#` section applies; otherwise the entry has no group
 * (`groupTitle = null`) and downstream normalization keys it as `other` (docs/02 §5.1 is
 * `group_key NOT NULL`).
 *
 * Malformed rows (one field, blank name, unusable URL) are counted in [ParseOutcome.skipped];
 * nothing throws.
 */
object TxtParser {

    private const val GENRE = "#genre#"

    fun parse(text: String, sourceId: String): ParseOutcome {
        val entries = ArrayList<RawEntry>()
        var lines = 0
        var skipped = 0
        var sectionGroup: String? = null

        for (rawLine in text.lineSequence()) {
            lines++
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val fields = line.split(',')

            // "#genre#" section marker: the group is everything before the final marker field.
            if (fields.last().trim().equals(GENRE, ignoreCase = true)) {
                sectionGroup = fields.dropLast(1).joinToString(",").trim().ifBlank { null }
                continue
            }

            // Any other '#' line is a comment.
            if (line.startsWith("#")) continue

            if (fields.size < 2) {
                skipped++
                continue
            }
            val name = fields[0].trim()
            val url = fields[1].trim().let { Urls.extract(it) }
            if (name.isEmpty() || !Urls.isValid(url)) {
                skipped++
                continue
            }
            val ownGroup = if (fields.size > 2) fields.drop(2).joinToString(",").trim() else ""
            val group = ownGroup.ifBlank { sectionGroup.orEmpty() }
            entries += RawEntry(
                name = name,
                url = url,
                groupTitle = group.ifBlank { null },
                sourceId = sourceId,
            )
        }

        return ParseOutcome(entries, PlaylistFormat.TXT, lines, skipped)
    }
}
