package ilab.iptv.player.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.log.ui.LogConsoleActivity
import ilab.iptv.player.core.ui.settings.SettingsContract
import ilab.iptv.player.feature.settings.diag.DiagnosticsActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The P2-8 settings skeleton (docs/04 P2-8 item 1, docs/02 §8.1's `SettingsActivity`).
 *
 * The page is a renderer over [SettingsCatalog]: the groups and their order, and which row leads where,
 * are decided (and unit-tested) in that catalog, so this class only inflates rows and routes clicks.
 * Two of the rows open pages that already exist — the P2-6 source manager and the P0-6/P1-8 device &
 * log console — and nothing is re-implemented here.
 *
 * Remote contract (docs/02 §8.2): every row is focusable and the focus path is a single vertical walk
 * down the page; BACK finishes the activity, which is the §8.1 返回键层级 contract "设置 → 浏览页"
 * ([SettingsHierarchy] states the same map, and the header prints it so a screenshot proves it).
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var header: TextView
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        header = findViewById(R.id.settings_header)
        rows = findViewById(R.id.settings_rows)
        header.text = getString(
            R.string.settings_header,
            getString(R.string.settings_title),
            getString(
                if (SettingsHierarchy.parentOf(SettingsScreen.SETTINGS) == SettingsScreen.BROWSE) {
                    R.string.settings_hint_back_browse
                } else {
                    R.string.settings_hint_back_settings
                },
            ),
        )

        lifecycleScope.launch {
            viewModel.facts.collect { facts -> render(facts) }
        }
    }

    override fun onResume() {
        super.onResume()
        // docs/03 §3.3 `UI_SCREEN_OPEN` is a DEBUG event: the diagnostics panel shows it after the
        // 记录级别 row is switched to DEBUG, which is exactly what the row is for.
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "settings opened",
            fields = mapOf("screen" to SCREEN_NAME, "from" to "Browse"),
        )
        lifecycleScope.launch { viewModel.load() }
    }

    private fun render(facts: SettingsFacts) {
        val focusedId = rows.findFocus()?.let { focus -> (focus.tag as? String) }
        rows.removeAllViews()
        SettingsCatalog.grouped(SettingsCatalog.build(facts)).forEach { (group, entries) ->
            rows.addView(groupTitle(group))
            entries.forEach { entry -> rows.addView(row(entry)) }
        }
        // Keep the remote where it was across a re-render (a toggle must not throw focus away);
        // on the first render the first focusable row takes it, so the page is never focus-less.
        val target = focusedId?.let { id -> rows.findViewWithTag<View>(id) } ?: firstFocusableRow()
        target?.let { if (!it.isFocused) it.requestFocus() }
    }

    private fun groupTitle(group: SettingsGroup): TextView =
        (layoutInflater.inflate(R.layout.item_settings_group, rows, false) as TextView).apply {
            text = getString(group.titleRes)
        }

    private fun row(entry: SettingsEntry): View =
        layoutInflater.inflate(R.layout.item_settings_entry, rows, false)
            .apply {
                tag = entry.id
                findViewById<TextView>(R.id.settings_row_title).text = getString(entry.titleRes)
                findViewById<TextView>(R.id.settings_row_summary).text =
                    getString(entry.summaryRes, *entry.summaryArgs.toTypedArray())
                isEnabled = entry.enabled
                alpha = if (entry.enabled) 1f else DISABLED_ALPHA
                setOnClickListener { activate(entry) }
            }

    /** The first row the remote can land on: group captions are not focusable on purpose (§8.2). */
    private fun firstFocusableRow(): View? =
        (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.isFocusable }

    private fun activate(entry: SettingsEntry) {
        when (entry.destination) {
            SettingsDestination.SOURCE_MANAGEMENT ->
                startActivity(SettingsContract.sourceManagementIntent(this))

            SettingsDestination.LOG_CONSOLE ->
                startActivity(Intent(this, LogConsoleActivity::class.java))

            SettingsDestination.DIAGNOSTICS ->
                startActivity(Intent(this, DiagnosticsActivity::class.java))

            SettingsDestination.CYCLE_LOG_LEVEL -> {
                val level = viewModel.cycleLogLevel()
                Toast.makeText(this, getString(R.string.settings_diag_level_summary, level.name), Toast.LENGTH_SHORT).show()
            }

            SettingsDestination.TOGGLE_FILE_LOG ->
                viewModel.toggleFileLog()

            // EPG-SETTINGS-1: the two EPG control faces. Both write through the persisted store; a
            // toast says what the value is now, the same shape the log-level row already uses.
            SettingsDestination.TOGGLE_EPG -> {
                val enabled = viewModel.toggleEpg()
                Toast.makeText(
                    this,
                    getString(
                        if (enabled) R.string.settings_epg_enabled_on else R.string.settings_epg_enabled_off,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            SettingsDestination.CYCLE_EPG_FRESHNESS -> {
                val intervalMs = viewModel.cycleEpgFreshness()
                Toast.makeText(
                    this,
                    getString(
                        R.string.settings_epg_freshness_summary,
                        SettingsCatalog.formatInterval(intervalMs),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            // A value row: nothing to open, and the click must not look broken.
            SettingsDestination.NONE -> Unit
        }
    }

    private companion object {
        const val SCREEN_NAME = "Settings"
        const val DISABLED_ALPHA = 0.5f
    }
}
