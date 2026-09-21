package ilab.iptv.player.core.log.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rotation / retention rules of docs/03 §6 are pure decisions over a directory listing, so they
 * are pinned here — no device, no filesystem.
 */
class LogFilePolicyTest {

    private val policy = LogFilePolicy(directoryPath = "/logs")
    private val today = "20260922"
    private val dayKey = fixedDayKey(today)
    private val nowMs = 1_700_000_000_000L

    private fun entry(name: String, size: Long = 10L, lastModifiedMs: Long = nowMs) =
        LogFileEntry(name = name, sizeBytes = size, lastModifiedMs = lastModifiedMs)

    private fun plan(entries: List<LogFileEntry>, active: String? = null): List<String> =
        policy.cleanupPlan(entries, nowMs, active?.let { policy.path(it) }, dayKey)

    // --- naming -----------------------------------------------------------------------------

    @Test
    fun `file names follow docs 03 section 6`() {
        assertThat(policy.activeFileName(today)).isEqualTo("iptv-20260922.log")
        assertThat(policy.segmentFileName(today, 1)).isEqualTo("iptv-20260922.1.log")
        assertThat(policy.activeFilePath(today)).isEqualTo("/logs/iptv-20260922.log")
        assertThat(policy.segmentFilePath(today, 2)).isEqualTo("/logs/iptv-20260922.2.log")
    }

    @Test
    fun `only its own files are managed`() {
        assertThat(policy.isManaged("iptv-20260922.log")).isTrue()
        assertThat(policy.isManaged("iptv-20260922.12.log")).isTrue()
        assertThat(policy.isManaged("crash-20260922.txt")).isFalse()
        assertThat(policy.isManaged("iptv-2026092.log")).isFalse()
        assertThat(policy.isManaged("iptv-20260922.log.bak")).isFalse()
        assertThat(policy.isManaged("notes.txt")).isFalse()
    }

    @Test
    fun `segment index is zero for the base file and n for its segments`() {
        assertThat(policy.segmentIndexOf("iptv-20260922.log")).isEqualTo(0)
        assertThat(policy.segmentIndexOf("iptv-20260922.7.log")).isEqualTo(7)
        assertThat(policy.segmentIndexOf("iptv-20260922.7.bak")).isNull()
        assertThat(policy.dayOf("iptv-20260922.7.log")).isEqualTo("20260922")
    }

    @Test
    fun `the next segment is one above the highest segment of that day`() {
        val existing = listOf(
            entry("iptv-20260922.log"),
            entry("iptv-20260922.1.log"),
            entry("iptv-20260922.3.log"),
            entry("iptv-20260921.9.log"),
        )

        assertThat(policy.nextSegmentFilePath(today, existing)).isEqualTo("/logs/iptv-20260922.4.log")
        assertThat(policy.nextSegmentFilePath(today, emptyList())).isEqualTo("/logs/iptv-20260922.1.log")
    }

    // --- retention --------------------------------------------------------------------------

    @Test
    fun `keeps the last seven days and drops what is older`() {
        val entries = listOf(
            entry("iptv-20260922.log"), // today
            entry("iptv-20260915.log"), // age 7 — still inside the window
            entry("iptv-20260914.log"), // age 8 — out
            entry("iptv-20260914.1.log"),
        )

        assertThat(plan(entries, active = "iptv-20260922.log")).containsExactly(
            "/logs/iptv-20260914.log",
            "/logs/iptv-20260914.1.log",
        )
    }

    @Test
    fun `the active file is never in the cleanup plan`() {
        val entries = listOf(entry("iptv-20260101.log", size = 10L))

        assertThat(plan(entries, active = "iptv-20260101.log")).isEmpty()
        assertThat(plan(entries)).containsExactly("/logs/iptv-20260101.log")
    }

    @Test
    fun `trims by total bytes oldest first and stops at the budget`() {
        val small = LogFilePolicy(directoryPath = "/logs", maxTotalBytes = 250L, maxFileCount = 99)
        val entries = listOf(
            entry("iptv-20260922.log", size = 100L), // active, newest
            entry("iptv-20260920.log", size = 100L),
            entry("iptv-20260919.log", size = 100L),
            entry("iptv-20260918.log", size = 100L), // oldest
        )

        val result = small.cleanupPlan(entries, nowMs, small.path("iptv-20260922.log"), dayKey)

        // 400 B > 250 B → the two oldest go, then the budget is met (200 B) and trimming stops.
        assertThat(result).containsExactly("/logs/iptv-20260918.log", "/logs/iptv-20260919.log")
    }

    @Test
    fun `trims by file count oldest first`() {
        val capped = LogFilePolicy(directoryPath = "/logs", maxFileCount = 3)
        val entries = listOf(
            entry("iptv-20260922.log"),
            entry("iptv-20260921.log"),
            entry("iptv-20260921.3.log"),
            entry("iptv-20260921.1.log"),
            entry("iptv-20260920.log"),
        )

        val result = capped.cleanupPlan(entries, nowMs, capped.path("iptv-20260922.log"), dayKey)

        // 5 files → 3: the oldest day goes first, then the day-21 base file (segment 0 before .1/.3).
        assertThat(result).containsExactly("/logs/iptv-20260920.log", "/logs/iptv-20260921.log")
    }

    @Test
    fun `expired files are deleted even when the budget is fine, and with a null active file`() {
        val entries = listOf(entry("iptv-20260922.log"), entry("iptv-20260101.2.log"))

        assertThat(plan(entries, active = "iptv-20260922.log")).containsExactly("/logs/iptv-20260101.2.log")
    }

    @Test
    fun `foreign files are ignored and do not consume the byte budget`() {
        val small = LogFilePolicy(directoryPath = "/logs", maxTotalBytes = 150L, maxFileCount = 99)
        val entries = listOf(
            entry("crash-20260920.txt", size = 900L),
            entry("iptv-20260922.log", size = 100L),
            entry("iptv-20260920.log", size = 100L),
        )

        val result = small.cleanupPlan(entries, nowMs, small.path("iptv-20260922.log"), dayKey)

        assertThat(result).containsExactly("/logs/iptv-20260920.log")
        assertThat(result).doesNotContain("/logs/crash-20260920.txt")
    }

    @Test
    fun `nothing is planned when every file is inside the rules`() {
        val entries = listOf(entry("iptv-20260922.log"), entry("iptv-20260921.log"), entry("iptv-20260921.1.log"))

        assertThat(plan(entries, active = "iptv-20260922.log")).isEmpty()
    }

    // --- day arithmetic ---------------------------------------------------------------------

    @Test
    fun `invalid day keys are rejected and leap days round-trip`() {
        assertThat(policy.dayIndexOf("20260922")).isNotNull()
        assertThat(policy.dayIndexOf("20240229")).isNotNull()
        assertThat(policy.dayIndexOf("20230229")).isNull()
        assertThat(policy.dayIndexOf("20261301")).isNull()
        assertThat(policy.dayIndexOf("20260231")).isNull()
        assertThat(policy.dayIndexOf("2026-09-22")).isNull()
        assertThat(policy.dayIndexOf("20260923")!! - policy.dayIndexOf("20260922")!!).isEqualTo(1L)
        assertThat(policy.dayIndexOf("20261001")!! - policy.dayIndexOf("20260930")!!).isEqualTo(1L)
    }

    @Test
    fun `the documented numbers are the defaults`() {
        assertThat(LogFilePolicy.DEFAULT_MAX_FILE_BYTES).isEqualTo(2L * 1024L * 1024L)
        assertThat(LogFilePolicy.DEFAULT_MAX_TOTAL_BYTES).isEqualTo(50L * 1024L * 1024L)
        assertThat(LogFilePolicy.DEFAULT_RETENTION_DAYS).isEqualTo(7)
        assertThat(policy.maxFileBytes).isEqualTo(LogFilePolicy.DEFAULT_MAX_FILE_BYTES)
        assertThat(policy.directory).isEqualTo("/logs")
        assertThat(LogFilePolicy(directoryPath = "/logs/").directory).isEqualTo("/logs")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non positive file ceiling`() {
        LogFilePolicy(directoryPath = "/logs", maxFileBytes = 0L)
    }
}
