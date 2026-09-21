package ilab.iptv.player.core.log.file

import java.io.File
import java.io.FileOutputStream

/**
 * Outcome of one append. The sink turns a failure into a degraded state (docs/03 §5: a sink must
 * not throw); carrying the reason makes the degradation reportable instead of silent.
 */
data class LogAppendResult(
    val ok: Boolean,
    val bytesWritten: Long = 0L,
    val reason: String? = null,
) {
    companion object {
        fun ok(bytesWritten: Long): LogAppendResult = LogAppendResult(ok = true, bytesWritten = bytesWritten)

        fun failed(reason: String): LogAppendResult = LogAppendResult(ok = false, reason = reason)
    }
}

/**
 * The file operations [ilab.iptv.player.core.log.FileSink] needs, behind an interface so the
 * rotation / retention / degradation logic can be unit-tested off-device (and so a failure can be
 * injected deliberately — "磁盘满/无权限" is the interesting case, not the happy one).
 *
 * Every implementation **must not throw**: an unusable filesystem is a degraded sink, not a crash
 * in the log pipeline.
 */
interface LogFileSystem {

    fun ensureDirectory(directory: String): Boolean

    /** Regular files in [directory] (unsorted); empty when it cannot be read. */
    fun list(directory: String): List<LogFileEntry>

    /** Size in bytes, `0` when the file does not exist or cannot be stat-ed. */
    fun size(path: String): Long

    fun append(path: String, text: String): LogAppendResult

    /** Forces what has been appended to reach the disk (the "flush 契约", docs/03 §5). */
    fun sync(path: String): Boolean

    fun rename(from: String, to: String): Boolean

    fun delete(path: String): Boolean
}

/**
 * Production implementation: plain `java.io`, no Android APIs (which also makes it usable from the
 * JVM unit tests). Each append opens, writes and closes, so a rotated or renamed file has no
 * writer holding it and there is no user-space buffer to lose on process death; `sync` is what
 * turns "in the page cache" into "on the disk".
 */
class JavaIoLogFileSystem : LogFileSystem {

    override fun ensureDirectory(directory: String): Boolean = try {
        val dir = File(directory)
        dir.isDirectory || dir.mkdirs()
    } catch (t: Throwable) {
        false
    }

    override fun list(directory: String): List<LogFileEntry> = try {
        val files = File(directory).listFiles() ?: return emptyList()
        files.filter { it.isFile }.map { LogFileEntry(it.name, it.length(), it.lastModified()) }
    } catch (t: Throwable) {
        emptyList()
    }

    override fun size(path: String): Long = try {
        val file = File(path)
        if (file.isFile) file.length() else 0L
    } catch (t: Throwable) {
        0L
    }

    override fun append(path: String, text: String): LogAppendResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return try {
            FileOutputStream(path, true).use { it.write(bytes) }
            LogAppendResult.ok(bytes.size.toLong())
        } catch (t: Throwable) {
            LogAppendResult.failed(describe(t))
        }
    }

    override fun sync(path: String): Boolean = try {
        val file = File(path)
        if (file.exists()) {
            // Opening in append mode and syncing the descriptor flushes the file's data blocks
            // without truncating anything; the descriptor carries no unflushed user-space buffer.
            FileOutputStream(file, true).use { it.fd.sync() }
            true
        } else {
            // Nothing was ever appended here: do **not** create the file just to fsync it (an
            // empty `iptv-*.log` right after a rotation would be noise in the file listing). A
            // missing parent directory is still a real failure.
            file.parentFile?.isDirectory == true
        }
    } catch (t: Throwable) {
        false
    }

    override fun rename(from: String, to: String): Boolean = try {
        File(from).renameTo(File(to))
    } catch (t: Throwable) {
        false
    }

    override fun delete(path: String): Boolean = try {
        val file = File(path)
        !file.exists() || file.delete()
    } catch (t: Throwable) {
        false
    }

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
}
