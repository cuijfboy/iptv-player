package ilab.iptv.player.core.epg

import java.io.Closeable
import java.io.InputStream

/**
 * E4's fetch result: **a handle, not the file** (docs/02 §6.3 "不整文件入内存").
 *
 * docs/02 §4.2 sketches `XmltvStream(sourceId, bytes)` and calls it a "流式句柄". Two fields alone
 * cannot be a handle — the body has to travel with it — so this keeps both of the documented fields
 * and adds the one thing that makes the type true to its own description:
 *
 * - [sourceId] / [bytes] — which EPG source answered, and the `Content-Length` the server declared
 *   (`null` for a chunked answer, which is what an unknown `bytes` means everywhere in the report).
 * - [body] — the **decoded** XMLTV character stream. A gzip body is already inflated by the provider
 *   (see [GzipSniffer]), so the parser never has to care which encoding arrived on the wire.
 *
 * **Ownership:** the consumer closes [body]. Nothing else keeps the connection alive, so a refresh
 * that walks away without closing leaks the socket until the transport's timeout fires.
 */
class XmltvStream(
    val sourceId: String,
    val bytes: Long?,
    val body: InputStream,
) : Closeable {

    override fun close() {
        runCatching { body.close() }
    }
}
