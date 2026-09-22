package ilab.iptv.player.feature.epg.grid

import ilab.iptv.player.core.model.Programme

/**
 * One channel's programmes, indexed for the two questions the grid asks every frame: *what is on at
 * this instant* (the focus cursor, the detail layer) and *what intersects this window* (the block
 * layout). Both are binary searches over a list sorted by `startMs` — no per-frame linear scan of a
 * channel's day, and no per-frame allocation of a sorted copy.
 *
 * Overlapping programmes are legal in XMLTV (a broadcaster may publish a corrected slot next to the
 * original), so [at] walks back a bounded number of entries and answers with the programme that starts
 * latest while still covering the instant — the most specific one.
 */
class ProgrammeIndex private constructor(
    val programmes: List<Programme>,
    private val starts: LongArray,
    private val stops: LongArray,
) {
    val size: Int get() = programmes.size
    val isEmpty: Boolean get() = programmes.isEmpty()

    /** The programme covering [atMs], or null when the instant falls in a gap (or before/after the guide). */
    fun at(atMs: Long): Programme? {
        var index = lastStartAtOrBefore(atMs)
        if (index < 0) return null
        var probe = 0
        while (index >= 0 && probe < OVERLAP_PROBE) {
            if (stops[index] > atMs) return programmes[index]
            index--
            probe++
        }
        return null
    }

    /** Programmes overlapping `[fromMs, toMs)`: `stop > fromMs && start < toMs`. */
    fun intersecting(fromMs: Long, toMs: Long): List<Programme> {
        if (toMs <= fromMs || programmes.isEmpty()) return emptyList()
        var first = firstStartAfter(fromMs)
        // An earlier programme may still be running at `fromMs`; walk back a bounded amount to find it.
        var probe = 0
        var back = first - 1
        while (back >= 0 && stops[back] > fromMs && probe < OVERLAP_PROBE) {
            first = back
            back--
            probe++
        }
        val out = ArrayList<Programme>()
        var i = first
        while (i < programmes.size && starts[i] < toMs) {
            if (stops[i] > fromMs) out += programmes[i]
            i++
        }
        return out
    }

    /** Earliest start, or null when empty — the frame builder uses it for the "guide ends" placeholder. */
    fun firstStartMs(): Long? = if (isEmpty) null else starts[0]

    /** Latest stop, or null when empty. Stops are not sorted when slots overlap, so this is a scan-free max of the tail. */
    fun lastStopMs(): Long? = if (isEmpty) null else stops[stops.size - 1]

    /** Index of the last entry with `start <= atMs`, or -1. */
    private fun lastStartAtOrBefore(atMs: Long): Int {
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= atMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Index of the first entry with `start > atMs`, or `size`. */
    private fun firstStartAfter(atMs: Long): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= atMs) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /** How far an overlap walk may go before it gives up; malformed data must not become a stall. */
        const val OVERLAP_PROBE = 16

        private val EMPTY = ProgrammeIndex(emptyList(), LongArray(0), LongArray(0))

        val empty: ProgrammeIndex get() = EMPTY

        /**
         * Builds the index. The list is copied and sorted by `(startMs, id)` once per window load — not
         * per frame — which is what keeps the per-frame work to two binary searches per visible row.
         */
        fun of(programmes: List<Programme>): ProgrammeIndex {
            if (programmes.isEmpty()) return EMPTY
            val sorted = programmes.sortedWith(compareBy({ it.startMs }, { it.id }))
            val starts = LongArray(sorted.size)
            val stops = LongArray(sorted.size)
            for (i in sorted.indices) {
                starts[i] = sorted[i].startMs
                stops[i] = sorted[i].stopMs
            }
            return ProgrammeIndex(sorted, starts, stops)
        }
    }
}
