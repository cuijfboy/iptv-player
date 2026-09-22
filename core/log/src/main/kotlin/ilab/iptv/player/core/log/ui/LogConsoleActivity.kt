package ilab.iptv.player.core.log.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.log.DeviceInfo
import ilab.iptv.player.core.log.FileSink
import ilab.iptv.player.core.log.LogBus
import ilab.iptv.player.core.log.MemoryRingSink
import ilab.iptv.player.core.log.R
import ilab.iptv.player.core.log.file.LogFileStorage
import java.util.Locale
import javax.inject.Inject

/**
 * P0-6 device page: device/build information plus the live memory-ring log (docs/03 §7.2, L1), and
 * the P1-8 on-disk log block — file switch, force-to-disk, manual cleanup, file list (docs/03
 * §6/§14, L3).
 *
 * Deliberately minimal — level switch, live tail, ring clear. Filtering/search, per-session
 * folding and the one-click export package belong to the P2-8 diagnostics panel and are not
 * implemented here.
 */
@AndroidEntryPoint
class LogConsoleActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var logBus: LogBus

    @Inject
    lateinit var memoryRingSink: MemoryRingSink

    @Inject
    lateinit var fileSink: FileSink

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private lateinit var deviceInfo: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var levelButton: Button
    private lateinit var fileToggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_console)

        deviceInfo = findViewById(R.id.device_info)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        levelButton = findViewById(R.id.level_button)
        fileToggleButton = findViewById(R.id.file_toggle_button)

        levelButton.setOnClickListener { cycleLevel() }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            memoryRingSink.clear()
            render()
        }
        fileToggleButton.setOnClickListener {
            // docs/03 §14 "写入日志文件" switch; the persisted settings page is P2-8.
            fileSink.enabled = !fileSink.enabled
            render()
        }
        findViewById<Button>(R.id.flush_button).setOnClickListener {
            // docs/03 §5 flush contract — the same call the crash/exit path uses.
            logger.flush(FILE_FLUSH_TIMEOUT_MS)
            render()
        }
        findViewById<Button>(R.id.cleanup_button).setOnClickListener {
            fileSink.cleanupNow()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // A real UI event through the real facade: proves the full pipeline (bus → queue → sinks)
        // on the device. DEBUG, so it needs the level switch — docs/03 §3.3 fixes this code's level.
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "log console opened",
            fields = mapOf("screen" to SCREEN_NAME),
        )
        handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
        render()
        // P3-7 item 4 (same guard as the diagnostics panel): never come back to a window with no
        // focused view — the buttons are the only focusables here, so one of them takes the remote.
        if (window.decorView.findFocus() == null) levelButton.requestFocus()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun cycleLevel() {
        logBus.minLevel = when (logBus.minLevel) {
            LogLevel.INFO -> LogLevel.DEBUG
            LogLevel.DEBUG -> LogLevel.VERBOSE
            else -> LogLevel.INFO
        }
        render()
    }

    private fun render() {
        deviceInfo.text = deviceReport()
        levelButton.text = getString(R.string.log_console_level, logBus.minLevel.name)
        fileToggleButton.text = getString(
            R.string.log_console_file_toggle,
            getString(if (fileSink.enabled) R.string.log_console_on else R.string.log_console_off),
        )

        val events = memoryRingSink.tail(RENDERED_LINES)
        logText.text = if (events.isEmpty()) {
            getString(R.string.log_console_empty)
        } else {
            events.joinToString(separator = "\n") { format(it) }
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun format(event: LogEvent): String {
        val fields = if (event.fields.isEmpty()) "" else " " + event.fields.entries.joinToString(",") { "${it.key}=${it.value}" }
        return buildString {
            append(event.elapsedMs).append("ms ")
            append(event.level.name).append(' ').append(event.category.name).append(' ')
            append(event.code).append(' ')
            append(event.message).append(fields)
        }
    }

    private fun deviceReport(): String = buildString {
        append("设备：").append(DeviceInfo.deviceSummary())
        append('\n')
        append("应用：").append(DeviceInfo.appVersion(this@LogConsoleActivity))
        append(" / 内存 ").append(DeviceInfo.usedMemoryMb()).append('/').append(DeviceInfo.maxMemoryMb()).append(" MB")
        append('\n')
        append("日志：级别 ").append(logBus.minLevel.name)
        append(" / 内存环 ").append(memoryRingSink.size).append('/').append(memoryRingSink.capacity)
        append("（淘汰 ").append(memoryRingSink.evictedCount).append('）')
        append(" / 队列积压 ").append(logBus.pendingCount)
        append('\n')
        append("计数：丢弃 ").append(logBus.droppedCount)
        append(" / 级别过滤 ").append(logBus.filteredCount)
        append(" / 信封失败 ").append(logBus.envelopeFailureCount)
        append(" / 会话 ").append(logBus.currentSessionId())
        append('\n')
        append(fileReport())
    }

    /**
     * The on-disk log block (docs/03 §6 L3 evidence): file switch, active file and the retention
     * numbers, the list of files with sizes, and the `adb pull` line a tester can copy.
     */
    private fun fileReport(): String {
        val status = fileSink.status()
        val builder = StringBuilder()
        if (status.directory == null) {
            builder.append(getString(R.string.log_console_file_unavailable, "无外部文件目录"))
        } else if (!status.enabled) {
            builder.append(getString(R.string.log_console_file_disabled, status.directory))
        } else {
            val files = fileSinkFiles(status.directory)
            builder.append(
                getString(
                    R.string.log_console_file_line,
                    status.directory,
                    status.activeFile ?: "-",
                    bytes(status.activeBytes),
                    status.fileCount,
                    bytes(status.totalBytes),
                    bytes(status.maxFileBytes),
                    status.maxFileCount,
                    bytes(status.maxTotalBytes),
                    status.retentionDays,
                ),
            )
            if (files.isNotEmpty()) {
                builder.append('\n').append("文件清单：").append(files.joinToString(", "))
            }
            if (!status.healthy) {
                builder.append('\n').append("文件日志异常：")
                    .append(status.lastError)
                    .append("（").append(status.retryInMs).append(" ms 后重试）")
            }
        }
        builder.append('\n').append(
            getString(
                R.string.log_console_file_counters,
                status.writtenLines,
                status.rotations,
                status.deletedFiles,
                status.writeFailures,
                status.syncFailures,
                status.skippedDegraded,
            ),
        )
        LogFileStorage.adbPullCommand(this)?.let {
            builder.append('\n').append(getString(R.string.log_console_file_pull, it))
        }
        return builder.toString()
    }

    /** `iptv-20260922.log (12.3 KB)` per file — the L3 "文件清单" evidence, read from the directory. */
    private fun fileSinkFiles(directory: String?): List<String> {
        if (directory == null) return emptyList()
        return java.io.File(directory).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.map { "${it.name} (${bytes(it.length())})" }
            ?: emptyList()
    }

    private fun bytes(value: Long): String = when {
        value >= MIB -> String.format(Locale.US, "%.1f MB", value / MIB.toDouble())
        value >= KIB -> String.format(Locale.US, "%.1f KB", value / KIB.toDouble())
        else -> "$value B"
    }

    private companion object {
        const val SCREEN_NAME = "LogConsole"
        const val REFRESH_INTERVAL_MS = 1000L
        const val RENDERED_LINES = 200
        const val FILE_FLUSH_TIMEOUT_MS = 2000L
        const val KIB = 1024L
        const val MIB = 1024L * KIB
    }
}
