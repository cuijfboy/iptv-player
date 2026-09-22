package ilab.iptv.player.feature.settings.diag

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.log.UrlRedactor
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * docs/03 §8's export package, end to end and off-device: the zip's entries, its contents, and — the
 * point of the whole round — that a token in a log line reaches the package as `***` and never as itself.
 *
 * The redactor under test is the **production** `UrlRedactor` (the same instance the log bus uses), so
 * this test also proves the two passes agree: the bus redacted the line before it hit the file, and the
 * export pipeline's second pass (§11/W7) leaves it exactly as it was.
 */
class DiagPackageWriterTest {

    private val root: File = Files.createTempDirectory("diag-export-test").toFile()
    private val writer = DiagPackageWriter(UrlRedactor())

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the package carries the overview, the stats, the device facts and the logs`() {
        val logFile = logFile(
            // A line as the FileSink would have written it *before* redaction existed, and a plain one.
            """{"seq":1,"code":"SRC_FETCH_OK","message":"fetch https://portal.example.com/live/user/secretkey/1.m3u8?token=SECRET1"}""",
            """{"seq":2,"code":"SRC_REFRESH_DONE","message":"done"}""",
            "",
        )
        val target = File(root, "export")
        val progress = mutableListOf<String>()

        val result = writer.write(request(logFile), target, progress::add)

        assertThat(result).isInstanceOf(DiagPackageResult.Written::class.java)
        val report = (result as DiagPackageResult.Written).report
        assertThat(report.entries).containsExactly(
            "meta.json",
            "stats.json",
            "overview.txt",
            "logs/iptv-20260922.log",
            "README.txt",
        ).inOrder()
        assertThat(report.logLines).isEqualTo(2)
        assertThat(report.logFileCount).isEqualTo(1)
        assertThat(report.sizeBytes).isGreaterThan(0L)
        assertThat(File(report.zipPath).isFile).isTrue()
        // The progress line the panel shows comes from the writer, step by step (docs/03 §8).
        assertThat(progress).containsAtLeast(DiagPackageWriter.STEP_META, DiagPackageWriter.STEP_LOGS, DiagPackageWriter.STEP_DONE)

        val entries = unzip(File(report.zipPath))
        assertThat(entries.keys).containsExactlyElementsIn(report.entries)
        assertThat(entries.getValue("overview.txt")).contains("== 设备 ==")
        assertThat(entries.getValue("overview.txt")).contains("机型：Sony BRAVIA")
        assertThat(entries.getValue("stats.json")).contains("\"channels\":658")
        assertThat(entries.getValue("meta.json")).contains("\"version\":\"0.5.2 (5)\"")
        assertThat(entries.getValue("README.txt")).contains("adb pull")
    }

    @Test
    fun `the export package is redacted - tokens become stars and the path becomes a hash`() {
        val logFile = logFile(
            """{"seq":1,"message":"fetch https://portal.example.com/live/user/secretkey/1.m3u8?token=SECRET1"}""",
        )

        val result = writer.write(request(logFile), File(root, "export")) as DiagPackageResult.Written
        val entries = unzip(File(result.report.zipPath))

        // The log half: the key is masked and the account/stream path is gone (docs/03 §11 rules 1-3).
        val logs = entries.getValue("logs/iptv-20260922.log")
        assertThat(logs).contains("token=***")
        assertThat(logs).doesNotContain("SECRET1")
        assertThat(logs).doesNotContain("secretkey")
        assertThat(logs).doesNotContain("1.m3u8")
        assertThat(logs).doesNotContain("live/user")
        assertThat(logs).contains("portal.example.com")

        // The JSON half: `meta.json` carries a subscription URL, and it is masked there too.
        val meta = entries.getValue("meta.json")
        assertThat(meta).contains("token=***")
        assertThat(meta).doesNotContain("SECRET2")
        // §11 masks the keys it lists and nothing else: a harmless query parameter survives, which is
        // what keeps a package useful for support (same rule as `UrlRedactorTest`'s `user=42`).
        assertThat(meta).contains("user=this-should-not-leak")
    }

    @Test
    fun `redaction is idempotent, so the package matches what the console showed`() {
        val line = """{"seq":1,"message":"fetch https://portal.example.com/live/user/secretkey/1.m3u8?token=SECRET1"}"""
        val redactedOnDisk = UrlRedactor().redact(line)
        val logFile = logFile(redactedOnDisk)

        val result = writer.write(request(logFile), File(root, "export")) as DiagPackageResult.Written
        val exported = unzip(File(result.report.zipPath)).getValue("logs/iptv-20260922.log").trim()

        assertThat(exported).isEqualTo(redactedOnDisk)
    }

    @Test
    fun `a target that cannot be created is a failure value, not an exception`() {
        val blocker = File(root, "blocker").apply { writeText("not a directory") }

        val result = writer.write(request(logFile("x")), File(blocker, "export"))

        assertThat(result).isInstanceOf(DiagPackageResult.Failed::class.java)
        assertThat((result as DiagPackageResult.Failed).message).contains("无法创建导出目录")
    }

    @Test
    fun `the file name is the documented one and carries the export time`() {
        assertThat(writer.fileName(1_700_000_000_000L)).matches("""iptv-diag-\d{8}-\d{6}\.zip""")
    }

    private fun request(logFile: File) = DiagPackageRequest(
        meta = linkedMapOf(
            "version" to "0.5.2 (5)",
            "source" to "https://portal.example.com/x.m3u8?token=SECRET2&user=this-should-not-leak",
            "exportedAt" to 1_700_000_000_000L,
        ),
        stats = linkedMapOf("channels" to 658, "streams" to 1316),
        overview = DiagOverview.build(
            DiagOverviewInput(
                appVersion = "0.5.2 (5)",
                abi = "arm64-v8a",
                sdk = "31 (Android 12)",
                deviceModel = "Sony BRAVIA",
                usedMemoryMb = 96,
                maxMemoryMb = 256,
                storageFreeBytes = 1_024L,
                storageTotalBytes = 4_096L,
                network = "WiFi / 已验证",
                channelCount = 658,
                groupCount = 5,
                streamCount = 1_316,
                hiddenCount = 0,
                favoriteCount = 0,
                sources = emptyList(),
                playback = DiagPlaybackSummary(0, 0, 0, null, null, null),
                logLevel = "INFO",
                fileLogEnabled = true,
                ringSize = 0,
                ringCapacity = 2000,
                fileLogSummary = "1 个 / 1.0 KB",
                lastRefresh = "还没刷新过",
            ),
        ),
        logFiles = listOf(logFile),
        extractionLines = listOf("adb pull /sdcard/Android/data/ilab.iptv.player/files/export ./tv-diag"),
        exportEpochMs = 1_700_000_000_000L,
    )

    private fun logFile(vararg lines: String): File {
        val dir = File(root, "logs").apply { mkdirs() }
        return File(dir, "iptv-20260922.log").apply { writeText(lines.joinToString("\n")) }
    }

    private fun unzip(zip: File): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                entries[entry.name] = input.readBytes().toString(Charsets.UTF_8)
                entry = input.nextEntry
            }
        }
        return entries
    }
}
