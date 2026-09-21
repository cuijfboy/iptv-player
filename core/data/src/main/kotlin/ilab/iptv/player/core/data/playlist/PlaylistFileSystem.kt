package ilab.iptv.player.core.data.playlist

import java.io.File

/** One file the import folder listing found. */
data class PlaylistFileEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

/**
 * The filesystem seam for local playlist import.
 *
 * It exists for the reason docs/02 §4.0 F4 gives about suspend entries: the import path is mostly
 * *edge cases* (folder missing, file vanished between listing and picking, unreadable bytes, a
 * full disk when keeping the copy), and those are the branches that must be unit-tested. A
 * one-method-per-operation interface lets a test fake every one of them without an emulator, while
 * production uses [AndroidPlaylistFileSystem].
 *
 * Failures travel as exceptions here (this is inside `:core:data`, one layer below the
 * `AppResult<T>` boundary the port exposes) — the importer catches them and turns them into a
 * user-facing [ilab.iptv.player.core.domain.playlist.ImportResult.Failed].
 */
interface PlaylistFileSystem {

    /** Regular files directly inside [folder]; empty when the folder does not exist. */
    fun listFiles(folder: String): List<PlaylistFileEntry>

    fun read(path: String): ByteArray

    /** Creates parent folders as needed; overwrites an existing file. */
    fun write(path: String, bytes: ByteArray)

    fun exists(path: String): Boolean

    fun size(path: String): Long

    /** Deletes one file; a missing file is not an error (the caller only wants it gone). */
    fun delete(path: String)
}

/** [PlaylistFileSystem] over `java.io.File` — the app's own external files dir needs no permission. */
class AndroidPlaylistFileSystem : PlaylistFileSystem {

    override fun listFiles(folder: String): List<PlaylistFileEntry> {
        val files = File(folder).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .map { PlaylistFileEntry(it.absolutePath, it.name, it.length(), it.lastModified()) }
    }

    override fun read(path: String): ByteArray = File(path).readBytes()

    override fun write(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun exists(path: String): Boolean = File(path).isFile

    override fun size(path: String): Long = File(path).length()

    override fun delete(path: String) {
        File(path).delete()
    }
}
