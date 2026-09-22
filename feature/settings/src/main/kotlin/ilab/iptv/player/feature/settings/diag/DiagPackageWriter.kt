package ilab.iptv.player.feature.settings.diag

import ilab.iptv.player.core.common.Redactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything the one-click export (docs/03 §8) writes. Assembled by the panel from the same sources
 * the overview uses, so the zip and the screen cannot disagree about what was measured.
 */
data class DiagPackageRequest(
    /** `meta.json`: 版本、构建、设备、导出时间、设置快照. */
    val meta: Map<String, Any?>,
    /** `stats.json`: the machine-readable form of the overview. */
    val stats: Map<String, Any?>,
    /** `overview.txt`: the same overview, rendered for a human. */
    val overview: List<DiagBlock>,
    /** `logs/`: the JSONL files the `FileSink` owns (docs/03 §6). */
    val logFiles: List<File>,
    /** The "how do I get it out" lines — `adb pull` and the on-TV route (docs/03 §8). */
    val extractionLines: List<String>,
    val exportEpochMs: Long,
)

/** What a successful export produced: where it is, how big it is, and what is inside. */
data class DiagPackageReport(
    val zipPath: String,
    val sizeBytes: Long,
    val entries: List<String>,
    val logLines: Int,
    val logFileCount: Int,
)

sealed interface DiagPackageResult {

    data class Written(val report: DiagPackageReport) : DiagPackageResult

    /** Never throws at the caller: the panel shows this line instead of crashing (docs/02 §11). */
    data class Failed(val message: String) : DiagPackageResult
}

/**
 * Writes `iptv-diag-<yyyyMMdd-HHmmss>.zip` (docs/03 §8) into the app's own external `files/export`
 * directory.
 *
 * The redaction contract of §11/W7 lives here: **every** string the package contains goes through
 * [redactor] on the way in — JSON values through [DiagJson.encode], text and log lines line-by-line —
 * so the package is masked even though the ring and the `FileSink` are not the exporter's business.
 * The second pass over an already-redacted log line is a no-op (`UrlRedactor` is idempotent), which is
 * what keeps the package's lines identical to the ones the console showed.
 *
 * Failure is a value, not an exception: a full disk or a device without an external files directory
 * has to reach the panel as a readable line (docs/03 §8 "导出过程要有进度与失败提示").
 */
class DiagPackageWriter(private val redactor: Redactor) {

    fun write(
        request: DiagPackageRequest,
        targetDir: File,
        onProgress: (String) -> Unit = {},
    ): DiagPackageResult = try {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            DiagPackageResult.Failed("无法创建导出目录：${targetDir.absolutePath}")
        } else {
            val zip = File(targetDir, fileName(request.exportEpochMs))
            val entries = mutableListOf<String>()
            var logLines = 0
            var logFileCount = 0
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                onProgress(STEP_META)
                out.writeEntry(META_ENTRY, DiagJson.encode(request.meta, redactor))
                entries += META_ENTRY

                onProgress(STEP_STATS)
                out.writeEntry(STATS_ENTRY, DiagJson.encode(request.stats, redactor))
                entries += STATS_ENTRY

                out.writeEntry(OVERVIEW_ENTRY, redactText(overviewText(request.overview)))
                entries += OVERVIEW_ENTRY

                onProgress(STEP_LOGS)
                request.logFiles.sortedBy { it.name }.forEach { file ->
                    val lines = redactedLines(file)
                    if (lines.isEmpty()) return@forEach
                    val entry = "$LOG_DIR/${file.name}"
                    out.writeEntry(entry, lines.joinToString(separator = "\n", postfix = "\n"))
                    entries += entry
                    logLines += lines.size
                    logFileCount++
                }

                out.writeEntry(README_ENTRY, readme(request))
                entries += README_ENTRY
            }
            onProgress(STEP_DONE)
            DiagPackageResult.Written(
                DiagPackageReport(
                    zipPath = zip.absolutePath,
                    sizeBytes = zip.length(),
                    entries = entries,
                    logLines = logLines,
                    logFileCount = logFileCount,
                ),
            )
        }
    } catch (t: Throwable) {
        DiagPackageResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /** `iptv-diag-<yyyyMMdd-HHmmss>.zip` in the device's own time zone (docs/03 §8). */
    fun fileName(epochMs: Long): String =
        "iptv-diag-${SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(Date(epochMs))}.zip"

    fun overviewText(blocks: List<DiagBlock>): String = buildString {
        blocks.forEach { block ->
            append("== ").append(block.title).append(" ==\n")
            block.facts.forEach { fact -> append(fact.label).append("：").append(fact.value).append('\n') }
            append('\n')
        }
    }

    private fun redactedLines(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { redactor.redact(it) }
    }

    private fun redactText(text: String): String =
        text.lines().joinToString(separator = "\n") { redactor.redact(it) }

    private fun readme(request: DiagPackageRequest): String = buildString {
        append("Munder Difflin 诊断包（docs/03 §8）\n\n")
        append("内容：\n")
        append("  meta.json      版本 / 构建 / 设备 / 导出时间 / 设置快照\n")
        append("  stats.json     运行概览的机器可读形式\n")
        append("  overview.txt   运行概览（人读）\n")
        append("  logs/          最近 7 天的 JSONL 日志（已脱敏）\n\n")
        append("脱敏（docs/03 §11）：URL 的账号、口令、查询参数一律替换为 ***，")
        append("路径替换为 8 位 hash；导出包在打包前再次经过 Redactor。\n\n")
        append("取出方式：\n")
        request.extractionLines.forEach { append("  ").append(redactor.redact(it)).append('\n') }
    }

    private fun ZipOutputStream.writeEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    companion object {
        const val LOG_DIR = "logs"
        const val META_ENTRY = "meta.json"
        const val STATS_ENTRY = "stats.json"
        const val OVERVIEW_ENTRY = "overview.txt"
        const val README_ENTRY = "README.txt"

        const val STEP_META = "写入 meta.json"
        const val STEP_STATS = "汇总统计"
        const val STEP_LOGS = "打包日志"
        const val STEP_DONE = "完成"

        private const val FILE_TIME_PATTERN = "yyyyMMdd-HHmmss"
    }
}
