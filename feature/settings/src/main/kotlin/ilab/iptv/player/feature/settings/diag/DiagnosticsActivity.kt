package ilab.iptv.player.feature.settings.diag

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
// The generated `R` lives in the module's base package; a subpackage has to import it explicitly.
import ilab.iptv.player.feature.settings.R
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The P2-8 diagnostics panel (docs/03 §7): 运行概览 (L4/L5's overview), 实时日志 (§7.2's filtering over
 * the **existing** memory ring) and §7.3's actions, including the one-click 诊断包 export of §8 (L8).
 *
 * Reading order on screen is literally docs/03 §7's: the overview says what the app currently thinks
 * its state is, the log tail says what just happened, and the actions take the evidence away.
 *
 * The panel owns no buffer and no repository: [DiagnosticsViewModel] reads the same `MemoryRingSink` /
 * `FileSink` the log bus writes to and the existing domain ports. BACK returns to the settings page
 * ([SettingsHierarchy]).
 */
@AndroidEntryPoint
class DiagnosticsActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    private val viewModel: DiagnosticsViewModel by viewModels()

    /** The view filter (§7.2). It changes what the panel *shows*, never what the bus *records*. */
    private var query = DiagLogQuery()
    private var paused = false
    private var shown: List<LogEvent> = emptyList()
    private var progressStep: String? = null

    private lateinit var header: TextView
    private lateinit var overview: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var levelButton: Button
    private lateinit var categoryButton: Button
    private lateinit var pauseButton: Button
    private lateinit var exportButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var epgButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private val refresh = object : Runnable {
        override fun run() {
            if (!paused) render()
            // The overview is a query, not a stream: re-read it every OVERVIEW_EVERY_TICKS seconds.
            if (!paused && tick % OVERVIEW_EVERY_TICKS == 0) lifecycleScope.launch { viewModel.refresh() }
            tick++
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        header = findViewById(R.id.diag_header)
        overview = findViewById(R.id.diag_overview)
        logText = findViewById(R.id.diag_log)
        logScroll = findViewById(R.id.diag_log_scroll)
        levelButton = findViewById(R.id.diag_level)
        categoryButton = findViewById(R.id.diag_category)
        pauseButton = findViewById(R.id.diag_pause)
        exportButton = findViewById(R.id.diag_export)
        progress = findViewById(R.id.diag_progress)
        epgButton = findViewById(R.id.diag_epg_refresh)

        val searchField = findViewById<EditText>(R.id.diag_search_field)
        levelButton.setOnClickListener {
            query = query.copy(minLevel = DiagLogFilter.cycleLevel(query.minLevel))
            render()
        }
        categoryButton.setOnClickListener {
            query = query.copy(categories = DiagLogFilter.cycleCategory(query.categories.singleOrNull())?.let { setOf(it) } ?: emptySet())
            render()
        }
        pauseButton.setOnClickListener {
            paused = !paused
            render()
        }
        findViewById<Button>(R.id.diag_search).setOnClickListener {
            query = query.copy(keyword = searchField.text.toString())
            render()
        }
        findViewById<Button>(R.id.diag_copy).setOnClickListener { copyVisibleLog() }
        findViewById<Button>(R.id.diag_clear).setOnClickListener {
            viewModel.clearRing()
            render()
        }
        exportButton.setOnClickListener { runExport() }
        epgButton.setOnClickListener { requestEpg() }

        lifecycleScope.launch { viewModel.progress.collect { step -> progressStep = step; render() } }
        lifecycleScope.launch { viewModel.blocks.collect { render() } }
    }

    override fun onResume() {
        super.onResume()
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "diagnostics panel opened",
            fields = mapOf("screen" to SCREEN_NAME, "from" to "Settings"),
        )
        lifecycleScope.launch { viewModel.refresh() }
        render()
        handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    /**
     * One export (docs/03 §8). The button is disabled for the duration so a second click while the zip
     * is being written cannot race the first one, and the outcome lands in the header *and* in a dialog
     * the tester can read — including the two ways to take the file off the TV.
     */
    private fun runExport() {
        exportButton.isEnabled = false
        lifecycleScope.launch {
            val result = viewModel.export()
            exportButton.isEnabled = true
            val message = when (result) {
                is DiagPackageResult.Written -> getString(
                    R.string.diag_export_done,
                    result.report.zipPath,
                    result.report.logFileCount,
                    result.report.logLines,
                    DiagOverview.bytes(result.report.sizeBytes),
                    getString(R.string.diag_export_how, DiagStorage.extractionLines(this@DiagnosticsActivity).joinToString("\n")),
                )

                is DiagPackageResult.Failed -> getString(R.string.diag_export_failed, result.message)
            }
            render()
            AlertDialog.Builder(this@DiagnosticsActivity)
                .setTitle(R.string.diag_export)
                .setMessage(message)
                .setPositiveButton(R.string.diag_export_close, null)
                .show()
        }
    }

    /**
     * P3-6's manual trigger. The button is disabled until the request returns so a second tap cannot
     * queue a second job, and the answer is shown as a dialog — the job itself is queued, not run
     * here, so "accepted" is the honest thing to report.
     */
    private fun requestEpg() {
        epgButton.isEnabled = false
        lifecycleScope.launch {
            val result = viewModel.refreshEpgNow()
            epgButton.isEnabled = true
            AlertDialog.Builder(this@DiagnosticsActivity)
                .setTitle(R.string.diag_epg_refresh)
                .setMessage(
                    getString(
                        if (result.accepted) R.string.diag_epg_requested else R.string.diag_epg_off,
                    ),
                )
                .setPositiveButton(R.string.diag_export_close, null)
                .show()
        }
    }

    /** §7.3 "复制全部日志到剪贴板". Copy what is on screen, so what is copied is what was read. */
    private fun copyVisibleLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val payload = shown.joinToString("\n") { format(it) }
        clipboard.setPrimaryClip(ClipData.newPlainText("iptv-diag-log", payload))
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.diag_copied, shown.size))
            .setPositiveButton(R.string.diag_export_close, null)
            .show()
    }

    private fun render() {
        overview.text = viewModel.blocks.value.joinToString(separator = "\n") { block ->
            "== ${block.title} ==\n" + block.facts.joinToString(separator = "\n") { "${it.label}：${it.value}" }
        }
        if (!paused) shown = viewModel.logs(query)
        logText.text = if (shown.isEmpty()) getString(R.string.diag_empty) else shown.joinToString("\n") { format(it) }
        if (!paused) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        val categoryLabel = query.categories.singleOrNull()?.name ?: getString(R.string.diag_category_all)
        header.text = getString(
            R.string.diag_header,
            DiagLogFilter.describe(query),
            getString(if (paused) R.string.diag_pause else R.string.diag_resume) + "·自动滚屏",
            shown.size,
            viewModel.logLevel().name,
            progressStep?.let { getString(R.string.diag_exporting, it) }
                ?: getString(R.string.settings_hint_back_settings),
        )
        levelButton.text = getString(R.string.diag_level, query.minLevel.name)
        categoryButton.text = getString(R.string.diag_category, categoryLabel)
        pauseButton.text = getString(if (paused) R.string.diag_resume else R.string.diag_pause)
        progress.isVisible = progressStep != null
    }

    /** One log line: elapsed time, level, category, code, message, then the fields and the session. */
    private fun format(event: LogEvent): String = buildString {
        append(event.elapsedMs).append("ms ").append(event.level.name).append(' ')
        append(event.category.name).append(' ').append(event.code).append(' ')
        append(event.message)
        if (event.fields.isNotEmpty()) {
            append(' ').append(event.fields.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
        append(" [").append(event.sessionId).append(']')
    }

    private companion object {
        const val SCREEN_NAME = "Diagnostics"
        const val REFRESH_INTERVAL_MS = 1_000L

        /** The overview re-reads the repositories every 10 s: fresh enough, and not a query per second. */
        const val OVERVIEW_EVERY_TICKS = 10
    }
}
