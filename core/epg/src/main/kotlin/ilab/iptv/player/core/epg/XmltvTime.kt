package ilab.iptv.player.core.epg

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV timestamps (docs/02 §6.3). The format is `YYYYMMDDhhmmss +HHMM`, and the offset is the part
 * that makes the value absolute — which is exactly why this is hand-written instead of a
 * `SimpleDateFormat` parse:
 *
 * - **an explicit offset is honoured, so DST is already inside the value.** `...120000 +0200` (CEST)
 *   and `...120000 +0100` (CET) are two different instants, one hour apart, and both are parsed
 *   exactly. There is no local-time reinterpretation and therefore no ambiguity to resolve.
 * - **a missing offset is read as UTC**, not as the device's zone. A guide that omits the offset is
 *   under-specified; guessing the *viewer's* zone would silently shift every programme by the
 *   difference between the feed and the TV, which is the one failure mode nobody can debug from the
 *   screen. UTC is wrong in a predictable way, and the refresh report counts these rows.
 * - `Z`, `+HH:MM` and `+HHMM` are accepted; seconds may be omitted (`YYYYMMDDhhmm`).
 *
 * Invalid values (month 13, hour 25, a year outside [MIN_YEAR, MAX_YEAR], unparseable text) answer
 * `null`; the caller counts them as skipped rows rather than guessing a time (docs/03 §3.3
 * `EPG_PARSE_OK`'s `skipped`).
 */
object XmltvTime {

    private const val MIN_YEAR = 1990
    private const val MAX_YEAR = 2100

    /**
     * When a `<programme>` carries no usable `stop`, the slot is given [DEFAULT_DURATION_MS]. Leaving
     * it out would make the row invisible to the "now" query (`start <= t < stop`), so a 30-minute
     * default is better than dropping the programme — and the parser counts it either way.
     */
    const val DEFAULT_DURATION_MS: Long = 30 * 60 * 1000L

    fun parse(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        val digits = value.takeWhile { it.isDigit() }
        if (digits.length != 12 && digits.length != 14) return null

        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length == 14) digits.substring(12, 14).toInt() else 0

        if (year !in MIN_YEAR..MAX_YEAR) return null
        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        val offsetMinutes = offsetMinutes(value.substring(digits.length)) ?: return null

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        // A day that does not exist in that month (`20260231`) rolls over in a lenient Calendar, which
        // would silently invent a time. Compare the fields back instead of trusting the rollover.
        if (calendar.get(Calendar.YEAR) != year ||
            calendar.get(Calendar.MONTH) != month - 1 ||
            calendar.get(Calendar.DAY_OF_MONTH) != day
        ) {
            return null
        }
        return calendar.timeInMillis - offsetMinutes * 60_000L
    }

    /** `+0800`, `+08:00`, `-0130`, `Z`, or blank (= UTC). Null when the remainder is not an offset. */
    private fun offsetMinutes(rest: String): Int? {
        val text = rest.trim()
        if (text.isEmpty()) return 0
        if (text == "Z" || text == "z") return 0

        val sign = when (text[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = text.substring(1).replace(":", "").trim()
        if (body.length != 4 || !body.all { it.isDigit() }) return null
        val hours = body.substring(0, 2).toInt()
        val minutes = body.substring(2, 4).toInt()
        if (hours > 14 || minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }
}
