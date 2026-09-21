package ilab.iptv.player.core.domain.playlist

/**
 * Local playlist import — the P2-6 slice that gives P1-4/P1-5/P1-7 and QA a *regular* way to get
 * real channels into the app instead of swapping the bundled fixture by hand.
 *
 * Deliberately one port with a tiny vocabulary: pick a file that is already on the device, read it,
 * run it through the existing parse → normalize → store pipeline, remember it across restarts.
 * Subscription URLs, editing and the enable/disable switch stay with the rest of P2-6.
 *
 * Domain purity (docs/02 §4.0 F3): the port speaks in `String` paths and plain data, never in
 * `java.io.File`, `Uri` or Android types, so the UI and the tests can both drive it.
 */

/** One file the user (or QA, via `adb push`) put in the import folder and can pick from the UI. */
data class ImportCandidate(
    /** Absolute path — the identity the caller passes back to [PlaylistImportPort.import]. */
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

/** The imported playlist the app is currently showing; survives a restart (a copy is kept). */
data class ImportedPlaylist(
    val name: String,
    val sourceId: String,
    val copiedPath: String,
    val sizeBytes: Long,
    val importedAtMs: Long,
    /**
     * The document the user picked through the system picker (`content://…`), or null when the file
     * came from the drop folder (P2-6 item 3). Kept so the screen can say where the list came from
     * and so the granted access is traceable after a restart (review R-27).
     */
    val sourceUri: String? = null,
)

/** What one successful import produced — the numbers the result dialog and the log carry. */
data class ImportReport(
    val name: String,
    val sourceId: String,
    /** `m3u` / `txt`, as the parser detected it. */
    val formatLabel: String,
    val rawEntries: Int,
    /** Rows the parser refused (docs/02 §6.1 Parse stage) — not an error count. */
    val skipped: Int,
    val channels: Int,
    val streams: Int,
    val elapsedMs: Long,
    /** Where the kept copy lives, so the failure/success text can name a real path. */
    val copiedPath: String,
)

/** Either the import happened or it did not — [Failed.message] is user-facing prose. */
sealed interface ImportResult {
    data class Done(val report: ImportReport) : ImportResult

    /**
     * [code] is an `EventCodes` value (already logged); [message] is what the screen shows, in the
     * same plain Chinese the rest of the UI uses.
     */
    data class Failed(val code: String, val message: String) : ImportResult
}

/** The two folders the feature needs to name in its UI (the drop folder is the `adb push` target). */
data class ImportFolders(val dropFolder: String, val storeFolder: String)

/**
 * What the browse screen binds to. All three calls are safe to run off the main thread and are
 * expected to be called there (they do file I/O); none of them blocks for longer than one file read.
 */
interface PlaylistImportPort {

    /** Where files go and where the kept copy goes — shown to the user, so it must be a real path. */
    fun folders(): ImportFolders

    /** Playlist-looking files currently in the drop folder, newest name order; empty when none. */
    suspend fun candidates(): List<ImportCandidate>

    /**
     * Parses [candidate] through the shipped pipeline and, **only if it yields at least one
     * channel**, replaces the current catalog with it (the `CatalogSink` write seam — Room in
     * production) and remembers it for the next start.
     * A rejected file leaves the current channel list untouched.
     */
    suspend fun import(candidate: ImportCandidate): ImportResult

    /**
     * Same import from a document the user picked in the system file picker (SAF,
     * `ACTION_OPEN_DOCUMENT`). [uri] is the opaque `content://…` string — the port stays Android-free,
     * and the implementation owns both the read and the persisted read permission.
     *
     * Two paths must stay available (P2-6 item 3): this one, because a U 盘 / USB stick has no
     * drop folder, and [candidates]/[import], because a TV may not ship a file picker at all.
     */
    suspend fun importUri(uri: String): ImportResult

    /** The remembered import, or null when the app is still on the bundled fixture. */
    suspend fun lastImported(): ImportedPlaylist?
}
