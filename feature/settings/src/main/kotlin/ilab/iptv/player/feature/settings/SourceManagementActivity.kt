package ilab.iptv.player.feature.settings

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.PlaylistHint
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceMutation
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P2-6 正篇 item 4: the source-management page (docs/02 §8.1's settings level).
 *
 * One screen holds everything the card asks for: the subscription list with its 最近结果, an add/edit
 * form (name / URL / format hint / enable switch), the enable switch as a row button, and the local
 * import button with **both** paths (P2-6 item 3): the system file picker (SAF) and the drop folder,
 * because a TV that ships no DocumentsUI can still be served by `adb push`.
 *
 * Remote contract (docs/02 §8.2): every action is a focusable button, `BACK` closes the open dialog
 * and otherwise returns to the browse screen (no new back-stack level is added — the page is opened
 * with `SettingsContract`), and every outcome is written to the header line so an adb-driven QA run
 * can read it after the toast has faded.
 */
@AndroidEntryPoint
class SourceManagementActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    /** Same port the browse screen uses; this module never sees `:core:data` (docs/02 §3.2 rule 2). */
    @Inject
    lateinit var importPort: PlaylistImportPort

    private val viewModel: SourceManagementViewModel by viewModels()

    private lateinit var header: TextView
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: SourceListAdapter

    private var rows: List<ManagedSource> = emptyList()
    private var lastMessage: String? = null

    /**
     * SAF picker (P2-6 item 3). The wildcard MIME type is deliberate: `.m3u`/`.txt` have no reliable
     * MIME type on a TV (many providers report `application/octet-stream`, some report nothing), so a
     * narrower filter would hide the user's own file. The importer validates the *content* anyway.
     */
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            report("没有选择文件")
            return@registerForActivityResult
        }
        lifecycleScope.launch { runImport { importPort.importUri(uri.toString()) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_management)

        header = findViewById(R.id.source_header)
        empty = findViewById(R.id.source_empty)
        list = findViewById(R.id.source_list)
        adapter = SourceListAdapter(
            onToggle = { source, enabled -> onToggle(source, enabled) },
            onEdit = { source -> showEditDialog(source) },
            onDelete = { source -> confirmDelete(source) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(false)

        findViewById<Button>(R.id.source_add).setOnClickListener { showEditDialog(null) }
        findViewById<Button>(R.id.source_import).setOnClickListener { showImportDialog() }

        lifecycleScope.launch {
            viewModel.sources.collect { sources ->
                rows = sources
                adapter.submitList(sources)
                render()
            }
        }
        lifecycleScope.launch { viewModel.load() }
    }

    override fun onResume() {
        super.onResume()
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "source management opened",
            fields = mapOf("screen" to SCREEN_NAME),
        )
        lifecycleScope.launch { viewModel.load() }
    }

    // --- actions ---------------------------------------------------------------------------------

    private fun onToggle(source: ManagedSource, enabled: Boolean) {
        lifecycleScope.launch {
            val mutation = viewModel.setEnabled(source.id, enabled)
            reportMutation(mutation) { saved ->
                getString(R.string.source_toggle_state, saved.label, toggleWord(saved.enabled))
            }
        }
    }

    private fun confirmDelete(source: ManagedSource) {
        AlertDialog.Builder(this)
            .setTitle(R.string.source_delete_title)
            .setMessage(getString(R.string.source_delete_message, source.label))
            .setNegativeButton(R.string.source_dialog_cancel, null)
            .setPositiveButton(R.string.source_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    val mutation = viewModel.delete(source.id)
                    reportMutation(mutation) { saved -> getString(R.string.source_deleted, saved.label) }
                }
            }
            .show()
    }

    /**
     * The add/edit form. The positive button is wired by hand so a rejected draft keeps the dialog
     * **open** with the reason in it — a closed dialog plus a toast would make the user retype what
     * they had already typed (docs/02 §11: 失败有可读提示).
     */
    private fun showEditDialog(existing: ManagedSource?) {
        val view = layoutInflater.inflate(R.layout.dialog_source_edit, null)
        val labelField = view.findViewById<EditText>(R.id.source_edit_label)
        val urlField = view.findViewById<EditText>(R.id.source_edit_url)
        val hintField = view.findViewById<Spinner>(R.id.source_edit_hint)
        val enabledField = view.findViewById<CheckBox>(R.id.source_edit_enabled)
        val error = view.findViewById<TextView>(R.id.source_edit_error)

        val hints = PlaylistHint.entries
        hintField.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            hints.map { it.label },
        )
        labelField.setText(existing?.label.orEmpty())
        urlField.setText(existing?.url.orEmpty())
        hintField.setSelection(hints.indexOf(existing?.hintOf() ?: PlaylistHint.AUTO).coerceAtLeast(0))
        enabledField.isChecked = existing?.enabled ?: true
        enabledField.setText(if (existing == null) R.string.source_field_enabled else R.string.source_field_keep_enabled)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.source_dialog_add else R.string.source_dialog_edit)
            .setView(view)
            .setNegativeButton(R.string.source_dialog_cancel, null)
            .setPositiveButton(R.string.source_dialog_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val draft = SourceDraft(
                    label = labelField.text.toString(),
                    url = urlField.text.toString(),
                    hint = hints[hintField.selectedItemPosition.coerceIn(hints.indices)],
                    enabled = enabledField.isChecked,
                )
                lifecycleScope.launch {
                    val mutation = if (existing == null) {
                        viewModel.add(draft)
                    } else {
                        viewModel.update(existing.id, draft)
                    }
                    when (mutation) {
                        is SourceMutation.Saved -> {
                            dialog.dismiss()
                            reportMutation(mutation) { saved -> getString(R.string.source_saved, saved.label) }
                        }

                        is SourceMutation.Rejected -> {
                            error.text = mutation.message
                            error.isVisible = true
                            report(mutation.message)
                        }

                        SourceMutation.Missing -> {
                            dialog.dismiss()
                            report(getString(R.string.source_missing))
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Which of the two import paths to take (P2-6 item 3: both must stay available). */
    private fun showImportDialog() {
        lifecycleScope.launch {
            val remembered = importPort.lastImported()
            val current = remembered?.name?.let { getString(R.string.source_import_current, it) }
                ?: getString(R.string.source_import_none)
            AlertDialog.Builder(this@SourceManagementActivity)
                .setTitle(R.string.source_import_title)
                .setMessage(getString(R.string.source_import_message) + "\n\n" + current)
                .setItems(
                    arrayOf(
                        getString(R.string.source_import_saf),
                        getString(R.string.source_import_drop),
                    ),
                ) { _, which ->
                    if (which == 0) openDocumentPicker() else showDropPicker()
                }
                .setNegativeButton(R.string.source_dialog_cancel, null)
                .show()
        }
    }

    private fun openDocumentPicker() {
        try {
            documentPicker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            // The TV-availability question the card asks about, answered at runtime: no picker means
            // no SAF, and the drop folder is still there.
            report(getString(R.string.source_import_saf_unavailable))
        }
    }

    /** The drop-folder path (TESTABLE-1), unchanged: files put there by `adb push` or a USB copy. */
    private fun showDropPicker() {
        lifecycleScope.launch {
            val candidates = importPort.candidates()
            if (candidates.isEmpty()) {
                AlertDialog.Builder(this@SourceManagementActivity)
                    .setTitle(R.string.source_import_title)
                    .setMessage(getString(R.string.source_import_empty, importPort.folders().dropFolder))
                    .setPositiveButton(R.string.source_import_close, null)
                    .show()
                return@launch
            }
            val labels = candidates.map { "${it.name}（${it.sizeBytes / 1024} KB）" }.toTypedArray()
            AlertDialog.Builder(this@SourceManagementActivity)
                .setTitle(R.string.source_import_title)
                .setItems(labels) { _, which ->
                    val candidate: ImportCandidate = candidates[which]
                    lifecycleScope.launch { runImport { importPort.import(candidate) } }
                }
                .setNegativeButton(R.string.source_dialog_cancel, null)
                .show()
        }
    }

    private suspend fun runImport(block: suspend () -> ImportResult) {
        val message = when (val result = block()) {
            is ImportResult.Done -> getString(
                R.string.source_import_done,
                result.report.name,
                result.report.channels,
                result.report.streams,
                result.report.formatLabel,
            )

            is ImportResult.Failed -> result.message
        }
        report(message)
    }

    private fun reportMutation(mutation: SourceMutation, message: (ManagedSource) -> String) {
        when (mutation) {
            is SourceMutation.Saved -> report(message(mutation.source))
            is SourceMutation.Rejected -> report(mutation.message)
            SourceMutation.Missing -> report(getString(R.string.source_missing))
        }
    }

    /** One place for "the last thing that happened": toast for the eye, header for the screenshot. */
    private fun report(message: String) {
        lastMessage = message
        render()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun render() {
        val enabled = rows.count { it.enabled }
        header.text = getString(R.string.source_summary, rows.size, enabled) +
            (lastMessage?.let { "\n$it" } ?: "")
        empty.isVisible = rows.isEmpty()
        if (rows.isEmpty() && list.findFocus() == null) {
            list.post { findViewById<Button>(R.id.source_add).requestFocus() }
        }
    }

    private fun toggleWord(enabled: Boolean): String =
        getString(if (enabled) R.string.source_enabled_state else R.string.source_disabled_state)

    private companion object {
        const val SCREEN_NAME = "SourceManagement"
    }
}

/** The hint a stored row implies: `kind` is what was written, and `TXT` is the only TXT hint. */
private fun ManagedSource.hintOf(): PlaylistHint =
    if (kind == ilab.iptv.player.core.model.SourceKind.TXT) PlaylistHint.TXT else PlaylistHint.M3U
