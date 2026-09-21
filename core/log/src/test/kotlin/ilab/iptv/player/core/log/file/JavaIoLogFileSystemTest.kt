package ilab.iptv.player.core.log.file

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Test

/**
 * The real `java.io` implementation — still off-device (the whole module is JVM-testable), but on a
 * real filesystem, so append/rename/delete/sync are exercised for real rather than faked.
 */
class JavaIoLogFileSystemTest {

    private val fs = JavaIoLogFileSystem()
    private val root = File(System.getProperty("java.io.tmpdir"), "iptv-log-test-${System.nanoTime()}")
    private val dir = File(root, "logs")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `creates the directory and appends utf8 lines`() {
        assertThat(fs.ensureDirectory(dir.absolutePath)).isTrue()
        assertThat(fs.ensureDirectory(dir.absolutePath)).isTrue()

        val first = fs.append(path("iptv-20260922.log"), """{"seq":1,"message":"频道"}""" + "\n")
        val second = fs.append(path("iptv-20260922.log"), """{"seq":2}""" + "\n")

        assertThat(first.ok).isTrue()
        assertThat(second.bytesWritten).isEqualTo(10L)
        assertThat(fs.size(path("iptv-20260922.log"))).isEqualTo(first.bytesWritten + second.bytesWritten)
        assertThat(File(dir, "iptv-20260922.log").readLines()).hasSize(2)
    }

    @Test
    fun `lists only the regular files of the directory with their size`() {
        fs.ensureDirectory(dir.absolutePath)
        fs.append(path("iptv-20260922.log"), "12345")
        fs.append(path("crash-1.txt"), "123")

        val entries = fs.list(dir.absolutePath).associateBy { it.name }

        assertThat(entries.keys).containsExactly("iptv-20260922.log", "crash-1.txt")
        assertThat(entries.getValue("iptv-20260922.log").sizeBytes).isEqualTo(5L)
        assertThat(entries.getValue("crash-1.txt").sizeBytes).isEqualTo(3L)
        assertThat(fs.list(File(root, "missing").absolutePath)).isEmpty()
    }

    @Test
    fun `renames and deletes a file`() {
        fs.ensureDirectory(dir.absolutePath)
        fs.append(path("iptv-20260922.log"), "data")

        assertThat(fs.rename(path("iptv-20260922.log"), path("iptv-20260922.1.log"))).isTrue()
        assertThat(fs.size(path("iptv-20260922.log"))).isEqualTo(0L)
        assertThat(fs.size(path("iptv-20260922.1.log"))).isEqualTo(4L)
        assertThat(fs.delete(path("iptv-20260922.1.log"))).isTrue()
        assertThat(fs.delete(path("iptv-20260922.1.log"))).isTrue()
        assertThat(fs.list(dir.absolutePath)).isEmpty()
    }

    @Test
    fun `sync reaches the disk without truncating the file`() {
        fs.ensureDirectory(dir.absolutePath)
        fs.append(path("iptv-20260922.log"), "kept")

        assertThat(fs.sync(path("iptv-20260922.log"))).isTrue()
        assertThat(fs.size(path("iptv-20260922.log"))).isEqualTo(4L)
    }

    @Test
    fun `syncing a file that was never written does not create it`() {
        fs.ensureDirectory(dir.absolutePath)

        assertThat(fs.sync(path("iptv-20260922.log"))).isTrue()
        assertThat(File(dir, "iptv-20260922.log").exists()).isFalse()
    }

    @Test
    fun `a missing directory or an impossible path fails instead of throwing`() {
        // No `ensureDirectory` call: the parent does not exist, exactly like a pulled SD card.
        val result = fs.append(path("iptv-20260922.log"), "line\n")

        assertThat(result.ok).isFalse()
        assertThat(result.reason).isNotNull()
        assertThat(fs.sync(path("iptv-20260922.log"))).isFalse()
        assertThat(fs.size(path("iptv-20260922.log"))).isEqualTo(0L)
        assertThat(fs.list(dir.absolutePath)).isEmpty()
    }

    private fun path(name: String): String = File(dir, name).absolutePath
}
