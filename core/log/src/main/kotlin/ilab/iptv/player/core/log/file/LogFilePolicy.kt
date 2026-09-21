package ilab.iptv.player.core.log.file

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One file in the log directory, as reported by [LogFileSystem.list].
 *
 * `lastModifiedMs` is only a tie-breaker: retention is judged from the **day in the file name**
 * (docs/03 §6 keeps files by day), so a copied or touched file cannot buy itself extra life.
 */
data class LogFileEntry(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/**
 * Naming / rotation / retention rules for the on-disk log (docs/03 §5 `FileSink`, §6 落盘与轮转).
 *
 * Numbers are the ones written down in docs/03 §6:
 * - `maxFileBytes` = **2 MB** (single-file ceiling) — also drives the size half of "按天 + 单文件 2 MB 双阈值";
 * - `retentionDays` = **7** and `maxTotalBytes` = **50 MB** ("保留 7 天或总量 50 MB，先删最旧").
 *
 * `maxFileCount` is **not** in docs/03 §6, so this default is a chosen one (recorded in
 * `docs/05-过程记录/13-P1-8日志落盘验证.md`): **12** files = 24 MB worst case. Since the BUG-011
 * fix it is a ceiling on **earlier days only** — the day's own segment files are exempt (they are
 * bounded by [maxTotalBytes] instead), so a busy day can never be reaped down by its own file count.
 *
 * This class is pure Kotlin — no Android and no filesystem — so every rule is unit-tested
 * off-device; the filesystem itself arrives through [LogFileSystem].
 */
data class LogFilePolicy(
    val directoryPath: String,
    val filePrefix: String = DEFAULT_FILE_PREFIX,
    val fileSuffix: String = DEFAULT_FILE_SUFFIX,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxFileCount: Int = DEFAULT_MAX_FILE_COUNT,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {

    init {
        require(directoryPath.isNotBlank()) { "directoryPath must not be blank" }
        require(filePrefix.isNotBlank()) { "filePrefix must not be blank" }
        require(fileSuffix.isNotBlank()) { "fileSuffix must not be blank" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive, was $maxFileBytes" }
        require(maxFileCount > 0) { "maxFileCount must be positive, was $maxFileCount" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive, was $maxTotalBytes" }
        require(retentionDays >= 0) { "retentionDays must not be negative, was $retentionDays" }
    }

    /** Trailing slashes would produce `//` in every path; normalise once. */
    val directory: String = directoryPath.trimEnd('/')

    private val namePattern: Regex = Regex(
        "^" + Regex.escape(filePrefix) + "-(\\d{8})(?:\\.(\\d+))?" + Regex.escape(fileSuffix) + "$",
    )

    /** Today's file: `iptv-20260922.log` (docs/03 §5). */
    fun activeFileName(day: String): String = "$filePrefix-$day$fileSuffix"

    /** A rotated segment of that day: `iptv-20260922.1.log`, `.2.log`, … (index starts at 1). */
    fun segmentFileName(day: String, index: Int): String = "$filePrefix-$day.$index$fileSuffix"

    fun activeFilePath(day: String): String = path(activeFileName(day))

    fun segmentFilePath(day: String, index: Int): String = path(segmentFileName(day, index))

    fun path(name: String): String = "$directory/$name"

    /** True for the files this sink owns; `crash-*.txt` and friends are left alone (docs/03 §5). */
    fun isManaged(name: String): Boolean = namePattern.matches(name)

    /** `20260922` for a managed name, `null` otherwise. */
    fun dayOf(name: String): String? = namePattern.matchEntire(name)?.groupValues?.get(1)

    /** `0` for the day's base file, `n` for `iptv-<day>.<n>.log`, `null` for anything else. */
    fun segmentIndexOf(name: String): Int? {
        val match = namePattern.matchEntire(name) ?: return null
        return match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: BASE_SEGMENT_INDEX
    }

    /**
     * Next free rotation target for [day]: one above the highest segment already on disk, so
     * rotation never overwrites a file (`renameTo` would fail or clobber).
     */
    fun nextSegmentFilePath(day: String, existing: List<LogFileEntry>): String {
        val highest = existing
            .filter { dayOf(it.name) == day }
            .mapNotNull { segmentIndexOf(it.name) }
            .maxOrNull() ?: BASE_SEGMENT_INDEX
        return segmentFilePath(day, maxOf(highest + 1, BASE_SEGMENT_INDEX + 1))
    }

    /**
     * Files to delete, **oldest first**, so the caller can simply delete them in order (docs/03 §6
     * "先删最旧"). Three reasons to delete, applied in this order (god 裁决 2026-09-22, BUG-011):
     * 1. the day in the name is older than [retentionDays];
     * 2. the copy-count ceiling [maxFileCount] is exceeded — this may only reap **earlier** days;
     * 3. the byte budget [maxTotalBytes] is exceeded — oldest day first, the day's own segments last.
     *
     * **Today's file is protected**: the active base file never enters the plan, and today's segment
     * files are exempt from the copy-count ceiling (they are bounded by the 50 MB budget alone), so
     * a busy day can never delete the log the tester is about to pull. The count / byte budget is
     * still measured over everything this sink owns, the active file included.
     *
     * `crash-*.txt`-style foreign files are ignored entirely — their budget belongs to their own
     * sink (docs/03 §5 keeps them at L4).
     */
    fun cleanupPlan(
        entries: List<LogFileEntry>,
        nowMs: Long,
        activeFilePath: String?,
        dayKey: DayKeyFormat,
    ): List<String> {
        val todayKey = dayKey.key(nowMs)
        val todayIndex = dayIndexOf(todayKey) ?: return emptyList()
        val todayBaseName = activeFileName(todayKey)
        val managed = entries.filter { isManaged(it.name) }

        // The active base file and today's base file are both untouchable: the first holds the newest
        // data, the second is where the next append goes (it looks inactive only because the sink has
        // not opened it yet).
        val candidates = managed
            .filter { path(it.name) != activeFilePath && it.name != todayBaseName }
            .sortedWith(
                compareBy(
                    { entryDayIndex(it, dayKey, todayIndex) },
                    { segmentIndexOf(it.name) ?: BASE_SEGMENT_INDEX },
                    { it.name },
                ),
            )
        if (candidates.isEmpty()) return emptyList()

        // Budget is measured over everything this sink owns, active file included.
        var managedTotalBytes = managed.sumOf { it.sizeBytes }
        var managedCount = managed.size

        val plan = mutableListOf<String>()
        val planned = mutableSetOf<String>()

        fun reap(entry: LogFileEntry) {
            plan += path(entry.name)
            planned += entry.name
            managedTotalBytes -= entry.sizeBytes
            managedCount -= 1
        }

        // ① Expired days go first, whatever the budgets say (never true for today's own files).
        candidates.forEach { entry ->
            if (todayIndex - entryDayIndex(entry, dayKey, todayIndex) > retentionDays) reap(entry)
        }

        // ② Copy-count ceiling: only earlier days may be reaped. Today's segments are exempt, so a
        //    chatty day cannot cut into its own evidence; older days are where the ceiling bites.
        candidates.forEach { entry ->
            if (entry.name !in planned && dayOf(entry.name) != todayKey && managedCount > maxFileCount) {
                reap(entry)
            }
        }

        // ③ Byte budget: oldest first, so earlier days go before today's segments; the budget stops
        //    exactly at the ceiling instead of deleting one file too many.
        candidates.forEach { entry ->
            if (entry.name !in planned && managedTotalBytes > maxTotalBytes) reap(entry)
        }

        return plan
    }

    /** Days since 1970-01-01 for a `yyyyMMdd` key, or `null` when the key is not a real date. */
    fun dayIndexOf(day: String): Long? = CivilDay.indexOf(day)

    private fun entryDayIndex(entry: LogFileEntry, dayKey: DayKeyFormat, fallback: Long): Long =
        dayOf(entry.name)?.let { dayIndexOf(it) }
            ?: runCatching { dayIndexOf(dayKey.key(entry.lastModifiedMs)) }.getOrNull()
            ?: fallback

    companion object {
        /** docs/03 §5: `FileSink` writes `files/logs/iptv-yyyyMMdd.log`. */
        const val DEFAULT_FILE_PREFIX = "iptv"
        const val DEFAULT_FILE_SUFFIX = ".log"

        /** docs/03 §6: 单文件 ≤ 2 MB. */
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L

        /** docs/03 §6: 总量 ≤ 50 MB（份数上限见类注释，文档未写死）. */
        const val DEFAULT_MAX_TOTAL_BYTES = 50L * 1024L * 1024L

        /** docs/03 §6: 保留 7 天. */
        const val DEFAULT_RETENTION_DAYS = 7

        /** Chosen default — 12 × 2 MB = 24 MB worst case for one busy day. */
        const val DEFAULT_MAX_FILE_COUNT = 12

        const val BASE_SEGMENT_INDEX = 0
    }
}

/** Turns a wall-clock instant into the `yyyyMMdd` day key used in file names. */
fun interface DayKeyFormat {
    fun key(epochMs: Long): String
}

/**
 * Real day key in the device's local time zone (`iptv-20260922.log` is "today" in the living room,
 * not in UTC). `SimpleDateFormat` is not thread safe, so the single format instance is locked —
 * log writes are single-threaded anyway, but the diagnostics page reads day keys from the UI thread.
 */
class WallClockDayKeyFormat(
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.US,
) : DayKeyFormat {

    private val lock = Any()
    private val format = SimpleDateFormat(DAY_KEY_PATTERN, locale).apply { this.timeZone = timeZone }

    override fun key(epochMs: Long): String = synchronized(lock) { format.format(Date(epochMs)) }

    companion object {
        const val DAY_KEY_PATTERN = "yyyyMMdd"
    }
}

/**
 * Days-since-epoch arithmetic for `yyyyMMdd` keys, used by the retention rule. Hand-rolled
 * (Howard Hinnant's civil-from-days) because `java.time` needs API 26 / core-library desugaring and
 * this module targets minSdk 21.
 */
internal object CivilDay {

    fun indexOf(day: String): Long? {
        if (day.length != DAY_LENGTH || day.any { !it.isDigit() }) return null
        val year = day.substring(0, 4).toInt()
        val month = day.substring(4, 6).toInt()
        val dayOfMonth = day.substring(6, 8).toInt()
        if (month !in 1..12 || dayOfMonth !in 1..31) return null
        val index = daysFromCivil(year, month, dayOfMonth)
        // Reject impossible dates (20260231) by round-tripping instead of trusting the range check.
        val (y, m, d) = civilFromDays(index)
        return if (y == year && m == month && d == dayOfMonth) index else null
    }

    fun daysFromCivil(year: Int, month: Int, dayOfMonth: Int): Long {
        val y = (year - if (month <= 2) 1 else 0).toLong()
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400L
        val shift = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153L * shift + 2L) / 5L + dayOfMonth - 1L
        val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    fun civilFromDays(index: Long): Triple<Int, Int, Int> {
        val z = index + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val year = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val dayOfMonth = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
        val month = monthPrime + if (monthPrime < 10L) 3L else -9L
        return Triple((year + if (month <= 2L) 1L else 0L).toInt(), month.toInt(), dayOfMonth.toInt())
    }

    private const val DAY_LENGTH = 8
}
