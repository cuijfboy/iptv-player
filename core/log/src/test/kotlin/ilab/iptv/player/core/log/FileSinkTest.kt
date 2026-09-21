package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.log.file.DayKeyFormat
import ilab.iptv.player.core.log.file.FakeLogFileSystem
import ilab.iptv.player.core.log.file.LogFilePolicy
import ilab.iptv.player.core.log.file.TestClock
import ilab.iptv.player.core.log.file.fixedDayKey
import ilab.iptv.player.core.log.file.testEvent
import org.junit.Test

/**
 * P1-8 acceptance behaviour of the on-disk sink (docs/03 §5/§6, L3): what lands on disk, when it
 * rotates, what gets cleaned, and what happens when the filesystem says no.
 */
class FileSinkTest {

    private val day = "20260922"
    private val dir = "/logs"
    private val basePath = "$dir/iptv-$day.log"
    private val dayMs = 1_700_000_000_000L

    private val fs = FakeLogFileSystem()
    private val clock = TestClock(dayMs)

    private fun sink(
        policy: LogFilePolicy = LogFilePolicy(directoryPath = dir),
        dayKey: DayKeyFormat = fixedDayKey(day),
        degradedRetryMs: Long = 60_000L,
    ) = FileSink(
        policy = policy,
        dayKey = dayKey,
        clock = clock,
        fs = fs,
        redactor = UrlRedactor(),
        degradedRetryMs = degradedRetryMs,
    )

    // --- happy path -------------------------------------------------------------------------

    @Test
    fun `writes one jsonl line per event into today's file`() {
        val sink = sink()

        sink.write(testEvent(seq = 1L, code = "APP_START"))
        sink.write(testEvent(seq = 2L, code = "UI_SCREEN_OPEN"))
        sink.write(testEvent(seq = 3L, code = "SRC_REFRESH_DONE"))

        assertThat(fs.lines(basePath)).hasSize(3)
        assertThat(fs.lines(basePath)[0]).startsWith("""{"seq":1,"ts":""")
        assertThat(fs.lines(basePath)[2]).contains(""""code":"SRC_REFRESH_DONE"""")
        assertThat(sink.status().writtenLines).isEqualTo(3L)
        assertThat(sink.status().activeFile).isEqualTo("iptv-$day.log")
        assertThat(sink.status().healthy).isTrue()
    }

    @Test
    fun `existing content of the day's file is counted before the first append`() {
        fs.seed(basePath, sizeBytes = 20L)

        val sink = sink()
        sink.write(testEvent())

        assertThat(sink.status().activeBytes).isEqualTo(fs.size(basePath))
        assertThat(sink.status().activeBytes).isGreaterThan(20L)
        assertThat(sink.status().fileCount).isEqualTo(1)
        assertThat(fs.lines(basePath)).hasSize(1)
    }

    @Test
    fun `rotates at the size ceiling and keeps the newest data in the base file`() {
        val policy = LogFilePolicy(directoryPath = dir, maxFileBytes = 200L, maxFileCount = 99)
        val sink = sink(policy)

        repeat(12) { index -> sink.write(testEvent(seq = index.toLong() + 1L, message = "event number $index")) }

        assertThat(sink.status().rotations).isAtLeast(1L)
        assertThat(fs.names()).contains("iptv-$day.1.log")
        assertThat(fs.size("$dir/iptv-$day.1.log")).isAtLeast(200L)
        assertThat(fs.size(basePath)).isLessThan(200L)
        // The last event may have been the one that rolled the file over, so look at all files.
        assertThat(fs.names().joinToString("") { fs.text("$dir/$it") }).contains("event number 11")
    }

    @Test
    fun `a file that grew outside the sink is rotated on the next write`() {
        // Ceiling above one line (~165 B) so the first write lands without rotating.
        val policy = LogFilePolicy(directoryPath = dir, maxFileBytes = 300L, maxFileCount = 99)
        val sink = sink(policy)
        sink.write(testEvent(seq = 1L))
        // Something else wrote into the file (a script, an earlier session): the sink must trust the
        // file, not its own counter, and roll over before appending.
        fs.nodes.getValue(basePath).content += ByteArray(500)

        sink.write(testEvent(seq = 2L))

        assertThat(sink.status().rotations).isEqualTo(1L)
        assertThat(fs.size("$dir/iptv-$day.1.log")).isAtLeast(500L)
        assertThat(fs.lines(basePath)).hasSize(1)
        assertThat(fs.text(basePath)).contains(""""seq":2""")
    }

    @Test
    fun `a new day starts a new file and leaves yesterday's file untouched`() {
        val switchMs = dayMs + 86_400_000L
        val sink = sink(dayKey = DayKeyFormat { if (it < switchMs) day else "20260923" })

        sink.write(testEvent(seq = 1L))
        val yesterday = fs.text(basePath)
        clock.now = switchMs
        sink.write(testEvent(seq = 2L))

        assertThat(fs.text(basePath)).isEqualTo(yesterday)
        assertThat(fs.lines("$dir/iptv-20260923.log")).hasSize(1)
        assertThat(sink.status().rotations).isEqualTo(0L)
        assertThat(sink.status().activeFile).isEqualTo("iptv-20260923.log")
    }

    // --- cleanup ----------------------------------------------------------------------------

    @Test
    fun `the first write cleans up what the retention rules no longer allow`() {
        fs.seed("$dir/iptv-20260101.log", sizeBytes = 10L)
        fs.seed("$dir/iptv-20260101.1.log", sizeBytes = 10L)
        fs.seed("$dir/crash-20260101.txt", sizeBytes = 10L)

        val sink = sink()
        sink.write(testEvent())

        assertThat(fs.names()).containsExactly("iptv-$day.log", "crash-20260101.txt")
        assertThat(sink.status().deletedFiles).isEqualTo(2L)
    }

    @Test
    fun `cleanupNow removes the files the policy plans and reports the count`() {
        fs.seed("$dir/iptv-20260101.log", sizeBytes = 10L)
        fs.seed("$dir/iptv-20260102.3.log", sizeBytes = 10L)
        val sink = sink()

        assertThat(sink.cleanupNow()).isEqualTo(2)
        assertThat(sink.cleanupNow()).isEqualTo(0)
        assertThat(sink.status().deletedFiles).isEqualTo(2L)
    }

    @Test
    fun `foreign files in the log directory are never deleted`() {
        fs.seed("$dir/crash-20260101.txt", sizeBytes = 10_000L)
        val sink = sink()

        sink.write(testEvent())
        sink.cleanupNow()

        assertThat(fs.names()).contains("crash-20260101.txt")
    }

    @Test
    fun `the manual cleanup keeps today's segments and reaps an earlier day instead`() {
        val capped = LogFilePolicy(directoryPath = dir, maxFileCount = 2)
        fs.seed("$dir/iptv-$day.1.log", sizeBytes = 10L)
        fs.seed("$dir/iptv-$day.2.log", sizeBytes = 10L)
        fs.seed("$dir/iptv-20260921.log", sizeBytes = 10L)
        val sink = sink(capped)

        // What the diagnostics page's 清理 button calls: 3 managed files, ceiling 2.
        assertThat(sink.cleanupNow()).isEqualTo(1)

        // The earlier day goes; both of today's segments survive, even though the ceiling is 2.
        assertThat(fs.names()).containsExactly("iptv-$day.1.log", "iptv-$day.2.log")
    }

    // --- degradation ------------------------------------------------------------------------

    @Test
    fun `a failed append degrades, counts, and retries at most once per window`() {
        val sink = sink(degradedRetryMs = 60_000L)
        fs.appendFailure = "ENOSPC: No space left on device"

        sink.write(testEvent(seq = 1L))
        sink.write(testEvent(seq = 2L))

        val degraded = sink.status()
        assertThat(degraded.writeFailures).isEqualTo(1L)
        assertThat(degraded.skippedDegraded).isEqualTo(1L)
        assertThat(degraded.healthy).isFalse()
        assertThat(degraded.lastError).contains("ENOSPC")
        assertThat(degraded.retryInMs).isEqualTo(60_000L)

        // Recovery: the next write after the window goes through and clears the error.
        clock.now += 60_000L
        fs.appendFailure = null
        sink.write(testEvent(seq = 3L))

        assertThat(sink.status().healthy).isTrue()
        assertThat(sink.status().writtenLines).isEqualTo(1L)
        assertThat(sink.status().skippedDegraded).isEqualTo(1L)
    }

    @Test
    fun `an unwritable directory degrades instead of crashing`() {
        fs.ensureDirectoryResult = false
        val sink = sink()

        sink.write(testEvent())

        assertThat(sink.status().writeFailures).isEqualTo(1L)
        assertThat(sink.status().lastError).contains("cannot create")
        assertThat(fs.names()).isEmpty()
    }

    @Test
    fun `a failed rotation degrades and keeps every written line`() {
        val policy = LogFilePolicy(directoryPath = dir, maxFileBytes = 120L, maxFileCount = 99)
        val sink = sink(policy)
        fs.renameFailure = true

        repeat(6) { index -> sink.write(testEvent(seq = index.toLong() + 1L, message = "line $index")) }

        assertThat(sink.status().writeFailures).isAtLeast(1L)
        assertThat(sink.status().lastError).contains("rotate failed")
        assertThat(fs.text(basePath)).contains("line 0")
    }

    @Test
    fun `a device without an external files directory drops events quietly`() {
        val sink = FileSink(
            policy = null,
            dayKey = fixedDayKey(day),
            clock = clock,
            fs = fs,
        )

        sink.write(testEvent())
        sink.flush()

        val status = sink.status()
        assertThat(status.directory).isNull()
        assertThat(status.skippedNoDirectory).isEqualTo(1L)
        assertThat(status.writeFailures).isEqualTo(0L)
        assertThat(fs.names()).isEmpty()
    }

    // --- switch + flush ---------------------------------------------------------------------

    @Test
    fun `the file switch stops and restarts the writing`() {
        val sink = sink()
        sink.enabled = false

        sink.write(testEvent())

        assertThat(fs.names()).isEmpty()
        assertThat(sink.status().skippedDisabled).isEqualTo(1L)

        sink.enabled = true
        sink.write(testEvent())

        assertThat(fs.lines(basePath)).hasSize(1)
    }

    @Test
    fun `flush syncs the active file and a sync failure is counted but never thrown`() {
        val sink = sink()
        sink.write(testEvent())

        sink.flush()

        assertThat(fs.syncCalls).isEqualTo(1)
        assertThat(sink.status().syncFailures).isEqualTo(0L)

        fs.syncFailure = true
        sink.flush()

        assertThat(sink.status().syncFailures).isEqualTo(1L)
        assertThat(sink.status().healthy).isFalse()
    }

    @Test
    fun `flush before anything was written is a no-op`() {
        val sink = sink()

        sink.flush()

        assertThat(fs.syncCalls).isEqualTo(0)
    }

    @Test
    fun `status reports the retention numbers the policy asked for`() {
        val policy = LogFilePolicy(directoryPath = dir, maxFileBytes = 1234L, maxTotalBytes = 4321L, maxFileCount = 3, retentionDays = 5)
        val sink = sink(policy)
        sink.write(testEvent())

        val status = sink.status()
        assertThat(status.maxFileBytes).isEqualTo(1234L)
        assertThat(status.maxTotalBytes).isEqualTo(4321L)
        assertThat(status.maxFileCount).isEqualTo(3)
        assertThat(status.retentionDays).isEqualTo(5)
        assertThat(status.directory).isEqualTo(dir)
    }

    @Test
    fun `id is stable for the hilt set`() {
        assertThat(sink().id).isEqualTo(FileSink.ID)
    }
}
