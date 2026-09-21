package ilab.iptv.player.core.data.playlist

import ilab.iptv.player.core.domain.playlist.ImportFolders
import javax.inject.Inject
import javax.inject.Singleton

/** A remembered import, ready to be parsed: the bytes plus the identity they were imported under. */
class RememberedPlaylist(val name: String, val sourceId: String, val bytes: ByteArray)

/**
 * What [ilab.iptv.player.core.data.catalog.ChannelCatalogLoader] needs at start-up: the last
 * imported playlist, or null when there is nothing (or nothing readable) to restore.
 */
interface RememberedPlaylistSource {
    fun read(): RememberedPlaylist?
}

/**
 * The kept copy of the last imported playlist plus its [ImportRecord], both in the app's own folder.
 *
 * Why a copy and not the original path: the file the user picked may be deleted, moved or (on the
 * device this is built for) sitting on removable storage that is not mounted at boot. Copying keeps
 * the promise "the channel list you imported is still there after a restart" true without depending
 * on anything outside the app's own directory — and the app's external files dir needs no storage
 * permission, which keeps `Publication`-style permission failures out of the boot path.
 */
@Singleton
class LastImportStore @Inject constructor(
    private val files: PlaylistFileSystem,
    private val folders: ImportFolders,
) : RememberedPlaylistSource {

    /**
     * The remembered playlist's bytes, or null when nothing was imported, the record is unreadable
     * or the kept copy is missing/empty. Every one of those is "use the bundled fixture instead".
     */
    override fun read(): RememberedPlaylist? {
        val record = record() ?: return null
        val bytes = try {
            files.read(record.copiedPath)
        } catch (_: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null
        return RememberedPlaylist(name = record.name, sourceId = record.sourceId, bytes = bytes)
    }

    /** The remembered record, or null when there is none / its copy is gone or empty. */
    fun record(): ImportRecord? {
        val decoded = readRecord() ?: return null
        if (!files.exists(decoded.copiedPath)) return null
        if (files.size(decoded.copiedPath) <= 0L) return null
        return decoded
    }

    /** Decodes `last-import.json` only; no check that the copy still exists. */
    fun readRecord(): ImportRecord? {
        val raw = try {
            files.read(recordPath())
        } catch (_: Exception) {
            return null
        }
        val text = try {
            raw.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return ImportRecord.decode(text)
    }

    /**
     * Keeps [bytes] under [name] in the store folder and writes the record.
     *
     * The previous copy is deleted when the name changed, so repeatedly importing different files
     * does not fill the device with dead playlists. The record is written **after** the copy: a
     * crash in between leaves an orphan copy, which is harmless, whereas the reverse order would
     * leave a record pointing at a file that does not exist.
     */
    fun write(record: ImportRecord, bytes: ByteArray): ImportRecord {
        val previous = readRecord()?.copiedPath
        files.write(record.copiedPath, bytes)
        if (previous != null && previous != record.copiedPath) {
            runCatching { files.delete(previous) }
        }
        files.write(recordPath(), record.encode().toByteArray(Charsets.UTF_8))
        return record
    }

    fun recordPath(): String = foldPath(RECORD_FILE)

    /** Where a copy of [name] would be kept (the store folder, file name sanitized). */
    fun copyPathFor(name: String): String = foldPath(sanitize(name))

    private fun foldPath(child: String): String = "${folders.storeFolder}/$child"

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { FALLBACK_NAME }
        val cleaned = base.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        return cleaned.ifBlank { FALLBACK_NAME }
    }

    private companion object {
        const val RECORD_FILE = "last-import.json"
        const val FALLBACK_NAME = "imported-playlist.m3u"
    }
}
