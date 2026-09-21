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
import ilab.iptv.player.core.log.LogBus
import ilab.iptv.player.core.log.MemoryRingSink
import ilab.iptv.player.core.log.R
import javax.inject.Inject

/**
 * P0-6 device page: device/build information plus the live memory-ring log (docs/03 §7.2, L1).
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_console)

        deviceInfo = findViewById(R.id.device_info)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        levelButton = findViewById(R.id.level_button)

        levelButton.setOnClickListener { cycleLevel() }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            memoryRingSink.clear()
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
    }

    private companion object {
        const val SCREEN_NAME = "LogConsole"
        const val REFRESH_INTERVAL_MS = 1000L
        const val RENDERED_LINES = 200
    }
}
