package ilab.iptv.player.feature.channels

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import ilab.iptv.player.core.model.EpgChannelRef

/**
 * The two P3-4 pickers, kept out of [BrowseActivity] so the remote-only dialog plumbing is in one
 * place.
 *
 * Both are plain framework dialogs on purpose. The rest of the browse screen avoids them for the row
 * actions because `setItems` + `setMessage` fight each other on AOSP (BUG-20260922-013); here the
 * content is a **custom view** (a search box plus a list), which that bug does not touch, and the
 * built-in list is what the D-pad drives.
 */
object ChannelManagerDialogs {

    /**
     * The guide-channel picker (P3-4 手动绑定 EPG). [candidates] is the guide's own `<channel>` list,
     * so every row is a binding the guide can actually serve.
     *
     * The honest-empty rule: a query that matches nothing says **该 guide 无此频道** — that is the
     * screen the three HK/MO/TW gaps (`TVB星河频道`/`澳视澳门`/`中天新闻`) produce, and it is why the
     * picker is fed by the parsed catalogue instead of a free-text id box. An empty catalogue is a
     * different message: the guide has not been pulled yet.
     */
    fun showEpgPicker(
        context: Context,
        channelName: String,
        candidates: List<EpgChannelRef>,
        currentId: String?,
        currentMatchLabel: String?,
        onPick: (EpgChannelRef) -> Unit,
    ): AlertDialog {
        val title = context.getString(R.string.browse_epg_picker_title, channelName)
        if (candidates.isEmpty()) {
            return AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(R.string.browse_epg_picker_no_catalog)
                .setPositiveButton(R.string.browse_import_close, null)
                .show()
        }

        val search = EditText(context).apply {
            hint = context.getString(R.string.browse_epg_picker_hint, candidates.size)
            setSingleLine()
        }
        val list = ListView(context)
        val empty = TextView(context).apply {
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
        }
        val listParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(search)
            addView(list, listParams)
            addView(empty)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(body)
            .setNegativeButton(R.string.browse_action_cancel, null)
            .create()

        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter
        var shown: List<EpgChannelRef> = emptyList()

        fun render(query: String) {
            shown = EpgPickerFilter.match(candidates, query)
            adapter.clear()
            adapter.addAll(shown.map { label(it, currentId) })
            adapter.notifyDataSetChanged()
            // The list stays visible even when empty so the remote has somewhere to go back from; the
            // message is a sibling view (never `setMessage`, which is the AOSP bug).
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            if (shown.isEmpty()) {
                empty.text = context.getString(R.string.browse_epg_picker_empty, query.trim())
            }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let {
                onPick(it)
                dialog.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })

        render("")
        dialog.show()
        return dialog
    }

    /** The group picker (P3-4 移动分组): the effective group titles already in the list. */
    fun showGroupPicker(
        context: Context,
        groupTitles: List<String>,
        onPick: (String) -> Unit,
    ): AlertDialog =
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.browse_group_picker_title, groupTitles.size))
            .setItems(groupTitles.toTypedArray()) { _, which -> onPick(groupTitles[which]) }
            .setNegativeButton(R.string.browse_action_cancel, null)
            .show()

    /** `name — id`, with the current binding marked so the user can see what is already set. */
    private fun label(ref: EpgChannelRef, currentId: String?): String {
        val mark = if (ref.id == currentId) " ✓" else ""
        return if (ref.displayName == ref.id) "${ref.id}$mark" else "${ref.displayName} — ${ref.id}$mark"
    }
}
