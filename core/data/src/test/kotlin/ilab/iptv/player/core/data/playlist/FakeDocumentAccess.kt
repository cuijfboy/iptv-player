package ilab.iptv.player.core.data.playlist

import java.io.IOException

/**
 * In-memory SAF seams (P2-6 item 3). The real adapters are two `ContentResolver` calls; the branches
 * that matter — the grant is refused, the document vanished between picking and reading — are what a
 * QA run cannot reproduce on demand, and this fake makes each one a single line.
 */
class FakeDocumentReader : DocumentReader {

    private val documents = LinkedHashMap<String, ByteArray>()
    private val names = mutableMapOf<String, String>()

    /** Uris whose [read] throws, simulating a document the grant no longer covers. */
    val unreadable = mutableSetOf<String>()

    fun put(uri: String, text: String, displayName: String? = null) {
        documents[uri] = text.toByteArray(Charsets.UTF_8)
        if (displayName != null) names[uri] = displayName
    }

    fun text(uri: String): String? = documents[uri]?.toString(Charsets.UTF_8)

    override fun read(uri: String): ByteArray {
        if (uri in unreadable) throw IOException("permission revoked: $uri")
        return documents[uri] ?: throw IOException("no document: $uri")
    }

    override fun displayName(uri: String): String? = names[uri]
}

/** Records the persisted grants the importer asks for, and can refuse them like the framework. */
class FakeUriPermissionStore : UriPermissionStore {

    private val persisted = LinkedHashSet<String>()

    /** Uris the framework refuses to make persistable (`take` throws `SecurityException`). */
    val refused = mutableSetOf<String>()

    val took = mutableListOf<String>()
    val released = mutableListOf<String>()

    override fun take(uri: String) {
        if (uri in refused) throw SecurityException("not persistable: $uri")
        took += uri
        persisted += uri
    }

    override fun release(uri: String) {
        released += uri
        persisted -= uri
    }

    override fun persisted(): List<String> = persisted.toList()
}
