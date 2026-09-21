package ilab.iptv.player.core.ui.import

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import ilab.iptv.player.core.ui.R

/**
 * The one place the local-import dialogs are built, so the bug that broke the regular entrance on TV
 * cannot come back through a copy of the old code.
 *
 * **BUG-20260922-013.** `AlertDialog.Builder(...).setMessage(hint).setItems(labels)` renders only the
 * message: AOSP `AlertController.setupContent()` attaches the list view **only when `mMessage ==
 * null`**, so the message path silently drops every item — no error, no empty state, just no rows:
 *
 * ```
 * if (mMessage != null) { mMessageView.setText(mMessage); … }
 * else { … if (mListView != null) { scrollParent.addView(mListView, …) } }
 * ```
 *
 * The fix keeps the framework's own list (so DPAD focus, selection and `OnItemClickListener` behave
 * exactly like the dialogs that do work) and moves the hint into a **custom title view** instead.
 * That is also why this class takes the hint as a parameter rather than letting a caller set a
 * message next to a list.
 */
object ImportPickerDialog {

    /**
     * Title (+ optional hint above the list) and a pick-able list with a cancel button.
     *
     * [onPick] receives the row index; callers map it through [ImportEntrance] rather than magic
     * numbers. Rows must be non-empty — the empty folder case is [showMessage].
     */
    fun showList(
        context: Context,
        title: String,
        rows: List<String>,
        cancelLabel: String,
        hint: String? = null,
        onPick: (Int) -> Unit,
    ): AlertDialog {
        require(rows.isNotEmpty()) { "an empty pick list has no rows to show; use showMessage()" }
        val builder = AlertDialog.Builder(context)
            .setItems(rows.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton(cancelLabel, null)
        if (hint.isNullOrBlank()) {
            builder.setTitle(title)
        } else {
            builder.setCustomTitle(titleView(context, title, hint))
        }
        return builder.show()
    }

    /**
     * Title + message + one close button. Every message-only dialog goes through here, which is the
     * shape AOSP renders correctly (and the one the "folder is empty" branch already used on device).
     */
    fun showMessage(
        context: Context,
        title: String,
        message: String,
        closeLabel: String,
    ): AlertDialog =
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(closeLabel, null)
            .show()

    /**
     * `setCustomTitle` replaces the framework's single-line title template with a view we own, so a
     * multi-line hint (current import + the folder path + the `adb push` line) fits without colliding
     * with the list.
     *
     * Inflated from [context] — the same context the dialog is built with — so the hint picks up the
     * dialog theme's text colours instead of guessing at them.
     */
    // No parent to inflate against on purpose: `setCustomTitle` hands the view to the dialog's top
    // panel, which is what supplies the layout params (AlertController.setupTitle does the same).
    @SuppressLint("InflateParams")
    private fun titleView(context: Context, title: String, hint: String): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_import_title, null)
        view.findViewById<TextView>(R.id.import_dialog_title).text = title
        view.findViewById<TextView>(R.id.import_dialog_hint).text = hint
        return view
    }
}
