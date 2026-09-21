package ilab.iptv.player.core.log.file

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.log.FileSink
import ilab.iptv.player.core.log.LogBus
import ilab.iptv.player.core.log.UptimeMs
import ilab.iptv.player.core.log.UrlRedactor
import java.io.File
import org.junit.After
import org.junit.Test

/**
 * End-to-end on a real filesystem: real [LogBus], real [FileSink], real `java.io`. This is the
 * closest a JVM test gets to the device behaviour of L3 — "文件落盘/轮转/清理生效" plus the flush
 * contract that the app start / exit / crash path relies on (docs/03 §5).
 */
class FileSinkPipelineTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "iptv-log-pipeline-${System.nanoTime()}")
    private val dir = File(root, "logs")
    private val day = "20260922"
    private val baseFile = File(dir, "iptv-$day.log")
    private val clock = MutableClock(1_700_000_000_000L)
    private val buses = mutableListOf<LogBus>()

    @After
    fun tearDown() {
        buses.forEach { it.close() }
        root.deleteRecursively()
    }

    @Test
    fun `events reach the disk, rotate, survive a restart, and flush the tail`() {
        val policy = LogFilePolicy(
            directoryPath = dir.absolutePath,
            maxFileBytes = 900L,
            // Room for all 20 events: this test is about rotation, not about reaping (that is the
            // next test plus the policy unit tests).
            maxFileCount = 99,
            maxTotalBytes = 10L * 1024L * 1024L,
        )
        val first = newBus(policy)

        repeat(20) { index ->
            first.i(
                category = LogCategory.SOURCE,
                code = "SRC_FETCH_OK",
                message = "channel list $index fetched from https://host/list.m3u8?token=SECRET$index",
                fields = mapOf("count" to index),
            )
        }
        // One more event after the batch: if the last one rolled the file over, this one lands in the
        // fresh base file — which is exactly the property "最新数据在基础文件".
        first.i(category = LogCategory.SOURCE, code = "SRC_REFRESH_DONE", message = "closing event")
        first.flush(FLUSH_TIMEOUT_MS)

        val files = dir.listFiles()!!.map { it.name }.sorted()
        assertThat(files.size).isAtLeast(2)
        assertThat(files).contains("iptv-$day.log")
        // At least one rotated segment exists (the oldest ones may already have aged out below).
        assertThat(files.any { it.matches(SEGMENT) }).isTrue()
        assertThat(files.size).isAtLeast(3)
        // Rotated files are near the ceiling; the live file never runs far past it.
        assertThat(baseFile.length()).isLessThan(2L * policy.maxFileBytes)
        assertThat(dir.listFiles()!!.filter { it.name.matches(SEGMENT) }.maxOf { it.length() })
            .isAtLeast(policy.maxFileBytes)
        // Every event landed exactly once: rotation renames files, it does not drop or duplicate.
        assertThat(allLines()).hasSize(21)
        assertThat(allLines().map(::seqOf)).containsExactlyElementsIn((1L..21L).toList())
        assertThat(allLines().count { it.contains("channel list 19 fetched") }).isEqualTo(1)
        assertThat(baseFile.readLines().last()).contains("closing event")
        // Redaction (docs/03 §11) happened before the bytes hit the disk.
        assertThat(allLines().joinToString("\n")).doesNotContain("SECRET")

        // Restart: a fresh bus + sink over the same directory keeps appending to the same file.
        val before = allLines().size
        val second = newBus(policy)
        second.i(category = LogCategory.APP, code = "APP_START", message = "app started")
        second.flush(FLUSH_TIMEOUT_MS)

        assertThat(allLines()).hasSize(before + 1)
        assertThat(allLines().count { it.contains("app started") }).isEqualTo(1)
    }

    @Test
    fun `startup cleanup removes an expired file before the first line is written`() {
        dir.mkdirs()
        val expired = File(dir, "iptv-20200101.log")
        expired.writeText("""{"seq":1}""" + "\n")
        val bus = newBus(LogFilePolicy(directoryPath = dir.absolutePath))

        bus.w(category = LogCategory.NET, code = "NET_REQ_FAIL", message = "refresh failed")
        bus.flush(FLUSH_TIMEOUT_MS)

        assertThat(expired.exists()).isFalse()
        assertThat(baseFile.readLines()).hasSize(1)
    }

    @Test
    fun `the file switch stops the disk writes while the bus keeps running`() {
        val sink = FileSink(
            policy = LogFilePolicy(directoryPath = dir.absolutePath),
            dayKey = { day },
            clock = clock,
        )
        val bus = LogBus(
            sinks = setOf(sink),
            clock = clock,
            uptime = { 0L },
            sessionIds = SequentialSessionIds(),
            redactor = UrlRedactor(),
        )
        buses += bus
        sink.enabled = false

        bus.i(category = LogCategory.APP, code = "APP_START", message = "app started")
        bus.flush(FLUSH_TIMEOUT_MS)

        assertThat(baseFile.exists()).isFalse()
        assertThat(sink.status().skippedDisabled).isEqualTo(1L)

        sink.enabled = true
        bus.i(category = LogCategory.APP, code = "APP_START", message = "app started")
        bus.flush(FLUSH_TIMEOUT_MS)

        assertThat(baseFile.readLines()).hasSize(1)
        assertThat(sink.status().writtenLines).isEqualTo(1L)
    }

    @Test
    fun `the copy ceiling reaps the oldest files on a real filesystem`() {
        dir.mkdirs()
        // Four days inside the retention window, so only the copy ceiling can be the reason to reap.
        (15..18).forEach { index -> File(dir, "iptv-202609$index.log").writeText("""{"seq":0}""" + "\n") }
        val bus = newBus(LogFilePolicy(directoryPath = dir.absolutePath, maxFileCount = 2))

        bus.i(category = LogCategory.APP, code = "APP_START", message = "app started")
        bus.flush(FLUSH_TIMEOUT_MS)

        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(
            "iptv-20260917.log",
            "iptv-20260918.log",
            "iptv-$day.log",
        )
    }

    private fun newBus(policy: LogFilePolicy): LogBus {
        val sink = FileSink(
            policy = policy,
            dayKey = { day },
            clock = clock,
            redactor = UrlRedactor(),
        )
        val bus = LogBus(
            sinks = setOf(sink),
            clock = clock,
            uptime = { 0L },
            sessionIds = SequentialSessionIds(),
            redactor = UrlRedactor(),
            initialMinLevel = LogLevel.INFO,
        )
        buses += bus
        return bus
    }

    /**
     * Every appended JSONL line in the directory, oldest file first (by mtime, name as tie-break —
     * rotation renames the file it just wrote, so mtime order is write order).
     */
    private fun allLines(): List<String> =
        (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy({ it.lastModified() }, { it.name }))
            .flatMap { file -> file.readLines().filter { it.startsWith("{") } }

    private fun seqOf(line: String): Long =
        SEQ.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    private class MutableClock(var now: Long) : Clock {
        override fun nowMs(): Long = now
    }

    private class SequentialSessionIds : SessionIdFactory {
        private var counter = 0
        override fun newId(prefix: String): String = "$prefix-${counter++}"
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
        val SEQ = Regex(""""seq":(\d+)""")
        val SEGMENT = Regex("""iptv-\d{8}\.\d+\.log""")
    }
}
