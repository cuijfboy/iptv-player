package ilab.iptv.player.core.ui.import

import ilab.iptv.player.core.domain.playlist.ImportCandidate

/**
 * The two entrances the local-import dialog offers, in the order they are listed on screen.
 *
 * P2-6 item 3 requires **both** to stay available: a USB stick has no drop folder, and a TV may ship
 * no file picker at all. Keeping the order in one enum means the label list and the index → action
 * mapping can never drift apart (the previous code hard-coded `if (which == 0)`).
 */
enum class ImportEntrance { SystemPicker, DropFolder }

/**
 * What the second step of the import dialog (the drop folder) shows, as data.
 *
 * BUG-20260922-013 was a *rendering* bug — the dialog showed its hint and threw the list away — so
 * the list-or-explanation decision is a pure function now, unit tested, instead of being discovered
 * by QA on a TV.
 */
sealed interface ImportPickContent {

    /** One row per pick-able file, in the order the rows are shown. */
    data class Files(val rows: List<String>) : ImportPickContent

    /**
     * Nothing to pick. The message already carries the path a file has to be put in (plus the
     * `adb push` line), and the caller shows one close button — the branch that already worked on
     * device and must keep working.
     */
    data class Empty(val message: String) : ImportPickContent
}

/** Pure decision: candidate files in → dialog content out. */
object ImportPickerModel {

    /**
     * The drop-folder step. [rowLabel] is a parameter so the row text stays the screen's business
     * (and so its test does not depend on the device time zone); production passes
     * [ImportCandidateLabel.describe].
     */
    fun pickContent(
        candidates: List<ImportCandidate>,
        emptyMessage: String,
        rowLabel: (ImportCandidate) -> String = { ImportCandidateLabel.describe(it) },
    ): ImportPickContent =
        if (candidates.isEmpty()) {
            ImportPickContent.Empty(emptyMessage)
        } else {
            ImportPickContent.Files(candidates.map(rowLabel))
        }
}
