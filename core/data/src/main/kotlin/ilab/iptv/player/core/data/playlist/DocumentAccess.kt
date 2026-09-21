package ilab.iptv.player.core.data.playlist

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.FileNotFoundException

/**
 * Reading a file the user picked in the system picker (SAF, `ACTION_OPEN_DOCUMENT`) — P2-6 item 3.
 *
 * `:core:domain`'s import port speaks in opaque `String` uris precisely so both of these seams can
 * stay here, in the one module that already owns file access. As with [PlaylistFileSystem], the
 * interface is one method per real failure mode: "the picker gave us a document the app may not
 * read" and "the read itself broke" are the branches a QA run cannot reproduce on demand, and a
 * fake covers both in one line.
 */
interface DocumentReader {

    /** Throws when the document is unreadable (permission revoked, removed, unplugged stick). */
    fun read(uri: String): ByteArray

    /** The picker's display name for [uri], or null when it does not expose one. */
    fun displayName(uri: String): String?
}

/**
 * Holds the *persisted* read grant for a picked document (review R-27).
 *
 * A SAF uri is only readable while the grant lives; without `takePersistableUriPermission` the bytes
 * are already copied into the app's own folder by the importer, so the app keeps working — but the
 * row could no longer point at (or re-read) the original document. Taking the grant is what makes
 * "the file I chose stays chosen" true across a restart, and it lives behind this seam so the
 * importer can be tested off-device.
 */
interface UriPermissionStore {

    /** Throws `SecurityException` when the framework refuses (the grant was not persistable). */
    fun take(uri: String)

    /** Releases a grant the app no longer needs; a uri without a grant is not an error. */
    fun release(uri: String)

    /** Every uri the app currently holds a persisted read grant for. */
    fun persisted(): List<String>
}

/** [DocumentReader] over `ContentResolver.openInputStream`. */
class ContentResolverDocumentReader(private val resolver: ContentResolver) : DocumentReader {

    override fun read(uri: String): ByteArray =
        resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            ?: throw FileNotFoundException("no stream for $uri")

    override fun displayName(uri: String): String? {
        val parsed = Uri.parse(uri)
        return runCatching {
            resolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
        }.getOrNull() ?: parsed.lastPathSegment
    }
}

/** [UriPermissionStore] over the framework's persisted-uri-permission list. */
class AndroidUriPermissionStore(private val resolver: ContentResolver) : UriPermissionStore {

    override fun take(uri: String) {
        resolver.takePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    override fun release(uri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun persisted(): List<String> =
        resolver.persistedUriPermissions.map { it.uri.toString() }
}
