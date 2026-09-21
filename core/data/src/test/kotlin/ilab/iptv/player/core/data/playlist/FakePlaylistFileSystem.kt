package ilab.iptv.player.core.data.playlist

import java.io.FileNotFoundException
import java.io.IOException

/**
 * In-memory [PlaylistFileSystem]: the whole point of the seam.
 *
 * Every import branch that matters is a filesystem edge case — the folder is empty, the file is
 * gone between listing and picking, the bytes are GB18030, the store folder is not writable — and
 * this fake makes all of them one line each. Production behaviour is `AndroidPlaylistFileSystem`,
 * which is a thin `java.io.File` adapter and has nothing else to get wrong.
 */
class FakePlaylistFileSystem : PlaylistFileSystem {

    private data class Node(val bytes: ByteArray, val modifiedAtMs: Long)

    private val nodes = LinkedHashMap<String, Node>()

    /** Paths whose [read] throws, simulating a file that vanished or cannot be opened. */
    val unreadable = mutableSetOf<String>()

    /** Paths whose [write] throws, simulating a full or read-only store folder. */
    val unwritable = mutableSetOf<String>()

    /** Stamp handed to [write]-created nodes; bump it to make "modified" times differ. */
    var clockMs: Long = 1_700_000_000_000L

    fun put(path: String, text: String, modifiedAtMs: Long = clockMs) {
        nodes[path] = Node(text.toByteArray(Charsets.UTF_8), modifiedAtMs)
    }

    fun putBytes(path: String, bytes: ByteArray, modifiedAtMs: Long = clockMs) {
        nodes[path] = Node(bytes, modifiedAtMs)
    }

    fun text(path: String): String? = nodes[path]?.bytes?.toString(Charsets.UTF_8)

    fun contains(path: String): Boolean = nodes.containsKey(path)

    override fun listFiles(folder: String): List<PlaylistFileEntry> = nodes
        .filterKeys { it.startsWith("$folder/") && !it.removePrefix("$folder/").contains('/') }
        .map { (path, node) ->
            PlaylistFileEntry(path, path.substringAfterLast('/'), node.bytes.size.toLong(), node.modifiedAtMs)
        }

    override fun read(path: String): ByteArray {
        if (path in unreadable) throw IOException("permission denied: $path")
        return nodes[path]?.bytes ?: throw FileNotFoundException(path)
    }

    override fun write(path: String, bytes: ByteArray) {
        if (path in unwritable) throw IOException("no space left on device: $path")
        nodes[path] = Node(bytes, clockMs)
    }

    override fun exists(path: String): Boolean = nodes.containsKey(path)

    override fun size(path: String): Long = nodes[path]?.bytes?.size?.toLong() ?: 0L

    override fun delete(path: String) {
        nodes.remove(path)
    }
}
