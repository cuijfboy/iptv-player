package ilab.iptv.player.core.log.file

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel

/**
 * In-memory [LogFileSystem] for the P1-8 tests: byte-accurate sizes, and every failure the real
 * device can throw at us ("磁盘满/无权限") injectable on demand. Keeping the sink's logic behind the
 * interface is what makes rotation/retention/degradation testable without an emulator.
 */
internal class FakeLogFileSystem : LogFileSystem {

    class Node(var content: ByteArray = ByteArray(0), var lastModifiedMs: Long = 0L)

    val nodes = linkedMapOf<String, Node>()

    /** Timestamp stamped on appended files; tests move it to model time. */
    var nowMs: Long = 0L

    var ensureDirectoryResult: Boolean = true
    var appendFailure: String? = null
    var syncFailure: Boolean = false
    var renameFailure: Boolean = false
    var deleteFailure: Boolean = false

    var appendCalls: Int = 0
    var syncCalls: Int = 0

    override fun ensureDirectory(directory: String): Boolean = ensureDirectoryResult

    override fun list(directory: String): List<LogFileEntry> =
        nodes.filterKeys { it.startsWith("$directory/") }
            .map { (path, node) -> LogFileEntry(path.substringAfterLast('/'), node.content.size.toLong(), node.lastModifiedMs) }

    override fun size(path: String): Long = nodes[path]?.content?.size?.toLong() ?: 0L

    override fun append(path: String, text: String): LogAppendResult {
        appendCalls++
        val failure = appendFailure
        if (failure != null) return LogAppendResult.failed(failure)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val node = nodes.getOrPut(path) { Node() }
        node.content += bytes
        node.lastModifiedMs = nowMs
        return LogAppendResult.ok(bytes.size.toLong())
    }

    override fun sync(path: String): Boolean {
        syncCalls++
        return !syncFailure && nodes.containsKey(path)
    }

    override fun rename(from: String, to: String): Boolean {
        if (renameFailure) return false
        val node = nodes.remove(from) ?: return false
        nodes[to] = node
        return true
    }

    override fun delete(path: String): Boolean {
        if (deleteFailure) return false
        return nodes.remove(path) != null
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Pre-existing file with a given size, e.g. one that has aged out of the retention window. */
    fun seed(path: String, sizeBytes: Long, lastModifiedMs: Long = 0L) {
        nodes[path] = Node(ByteArray(sizeBytes.toInt()), lastModifiedMs)
    }

    fun text(path: String): String = nodes[path]?.content?.toString(Charsets.UTF_8).orEmpty()

    fun lines(path: String): List<String> = text(path).lines().filter { it.isNotEmpty() }

    fun names(): List<String> = nodes.keys.map { it.substringAfterLast('/') }
}

/** [Clock] the tests move by hand. */
internal class TestClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

/** A [DayKeyFormat] that always answers the same day. */
internal fun fixedDayKey(day: String): DayKeyFormat = DayKeyFormat { day }

internal fun testEvent(
    seq: Long = 1L,
    level: LogLevel = LogLevel.INFO,
    category: LogCategory = LogCategory.APP,
    code: String = "APP_START",
    message: String = "message",
    fields: Map<String, Any?> = emptyMap(),
    error: Throwable? = null,
    thread: String = "iptv-log",
    screen: String? = null,
    sessionId: String = "app-0001",
): LogEvent = LogEvent(
    seq = seq,
    ts = 1_700_000_000_000L + seq,
    elapsedMs = seq * 10L,
    level = level,
    category = category,
    code = code,
    message = message,
    fields = fields,
    error = error,
    thread = thread,
    screen = screen,
    sessionId = sessionId,
)
