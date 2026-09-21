package ilab.iptv.player.core.data.playlist

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.DispatcherProvider
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.catalog.CatalogLoadReport
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportReport
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.ImportedPlaylist
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local playlist import for the P2-6 slice: pick a file that is already on the device, run it
 * through the shipped pipeline, keep a copy so a restart still has channels.
 *
 * Why this exists at all: the bundled catalog is a synthetic `demo.invalid` fixture, so on a real
 * device nothing can actually play. Before this, the only way to see a picture was to swap
 * `core:data`'s asset and rebuild (`docs/05-过程记录/16-P1-4播放界面验证.md` §7 开放项 2). QA needs a
 * *regular* entrance for P1-5/P1-7 and the end-to-end acceptance, and that is this.
 *
 * Design decisions worth keeping:
 * - **Explicit path, not SAF.** The drop folder is the app's own external files dir
 *   (`Android/data/ilab.iptv.player/files/playlists`), which needs *no* storage permission and is
 *   writable by `adb push` — the QA flow in `docs/05-过程记录/19-本地导入验证.md` §4. A tree-picker
 *   (`ACTION_OPEN_DOCUMENT`) was rejected for this slice because it needs a DocumentsUI that the TV
 *   may not ship and a persistable-URI dance (review R-27) that the settings screen should own
 *   (P2-6 proper).
 * - **All or nothing.** `prepare` does not touch the store, so a file that parses to zero channels
 *   (the common case: a stale dump, a wrong file, an empty export) is rejected *before* it can wipe
 *   the list the user is watching.
 * - **Copy first, record second, publish third.** A crash leaves either nothing or a complete file;
 *   the store is only replaced once the copy is safely on disk.
 * - **All file I/O runs on the injected `DispatcherProvider.io`** (docs/02 §4.5 C6), never on
 *   `Dispatchers.IO` directly, so a test can hand in a `TestDispatcher` and see the work land there.
 */
@Singleton
class LocalPlaylistImportRepository @Inject constructor(
    private val files: PlaylistFileSystem,
    private val folders: ImportFolders,
    private val lastImport: LastImportStore,
    private val catalog: ChannelCatalog,
    private val logger: Logger,
    private val clock: Clock,
    private val sessionIds: SessionIdFactory,
    /**
     * Same byte ceiling the Fetch stage uses (docs/02 §4.4 `maxBytes`, 8 MB): a file that big is not
     * a playlist, and reading it whole just to find that out would be the one place this feature
     * could hurt a memory-constrained TV.
     */
    private val limits: PipelineLimits,
    private val dispatchers: DispatcherProvider,
    /**
     * SAF read seam (P2-6 item 3). Only [importUri] uses it; the drop-folder path stays on
     * [PlaylistFileSystem] so a TV without a DocumentsUI still has a working import.
     */
    private val documents: DocumentReader,
    /** Holds the persisted read grant for a picked document (review R-27). */
    private val permissions: UriPermissionStore,
) : PlaylistImportPort {

    override fun folders(): ImportFolders = folders

    override suspend fun candidates(): List<ImportCandidate> = withContext(dispatchers.io) {
        files.listFiles(folders.dropFolder)
            .filter { it.sizeBytes > 0L && isPlaylistName(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { ImportCandidate(it.path, it.name, it.sizeBytes, it.modifiedAtMs) }
    }

    override suspend fun import(candidate: ImportCandidate): ImportResult = withContext(dispatchers.io) {
        val session = sessionIds.newId("import")
        val bytes = try {
            files.read(candidate.path)
        } catch (e: Exception) {
            return@withContext failed(
                code = EventCodes.DB_FAIL,
                name = candidate.name,
                message = "读不到这个文件：${candidate.name}",
                reason = "read failed",
                session = session,
                failure = AppError.storage(EventCodes.DB_FAIL, e),
                error = e,
            )
        }
        publish(name = candidate.name, bytes = bytes, sourceUri = null, session = session)
    }

    /**
     * The SAF path: a document the user picked in the system picker. Same pipeline, same
     * all-or-nothing rule — the only differences are where the bytes come from and that the
     * persisted read grant is taken first, so the row keeps pointing at the document it came from.
     */
    override suspend fun importUri(uri: String): ImportResult = withContext(dispatchers.io) {
        val session = sessionIds.newId("import")
        try {
            permissions.take(uri)
        } catch (e: SecurityException) {
            return@withContext failed(
                code = EventCodes.DB_FAIL,
                name = uri,
                message = "没有拿到这个文件的长期读取权限，请重新选择：${displayNameOf(uri)}",
                reason = "uri permission refused",
                session = session,
                failure = AppError.storage(EventCodes.DB_FAIL, e),
                error = e,
            )
        }

        val bytes = try {
            documents.read(uri)
        } catch (e: Exception) {
            permissions.release(uri)
            return@withContext failed(
                code = EventCodes.DB_FAIL,
                name = uri,
                message = "读不到这个文件：${displayNameOf(uri)}",
                reason = "uri read failed",
                session = session,
                failure = AppError.storage(EventCodes.DB_FAIL, e),
                error = e,
            )
        }
        publish(name = displayNameOf(uri), bytes = bytes, sourceUri = uri, session = session)
    }

    /**
     * The shared import: bytes in, catalog replaced and remembered. Every failure mode here is one
     * the QA flow would otherwise have to reproduce by hand, and the ordering is the point —
     * **copy first, record second, publish third**, so a crash never leaves a record pointing at a
     * file that does not exist, and a rejected file never wipes the list the user is watching.
     */
    private suspend fun publish(
        name: String,
        bytes: ByteArray,
        sourceUri: String?,
        session: String,
    ): ImportResult {
        val startedAt = clock.nowMs()
        val sourceId = sourceIdFor(name)

        if (bytes.isEmpty()) {
            return failed(
                code = EventCodes.SRC_PARSE_FAIL,
                name = name,
                message = "文件是空的：$name",
                reason = "empty file",
                session = session,
                failure = AppError.parse(EventCodes.SRC_PARSE_FAIL),
            )
        }
        if (bytes.size > limits.maxBytes) {
            return failed(
                code = EventCodes.SRC_PARSE_FAIL,
                name = name,
                message = "文件过大（${bytes.size / MEGABYTE} MB），不像是播放列表：$name",
                reason = "file too large",
                session = session,
                failure = AppError.parse(EventCodes.SRC_PARSE_FAIL),
            )
        }

        val prepared = when (val result = catalog.prepare(bytes, sourceId, charsetHint = null)) {
            is AppResult.Err -> {
                return failed(
                    code = EventCodes.SRC_PARSE_FAIL,
                    name = name,
                    message = "解析失败，不是可识别的 M3U/TXT：$name",
                    reason = "parse failed",
                    session = session,
                    failure = result.error,
                    error = result.error.cause,
                )
            }

            is AppResult.Ok -> result.value
        }

        val report = prepared.report
        if (report.channels == 0) {
            // Parsed fine, but nothing usable came out of it. Log the parse numbers first: "0 rows"
            // and "500 rows, 500 skipped" are different problems and the reader must be able to tell
            // them apart.
            logParsed(name, report, session, startedAt)
            return failed(
                code = EventCodes.SRC_PARSE_FAIL,
                name = name,
                message = "清单里没有可用频道（共 ${report.lines} 行，解析出 ${report.rawEntries} 条，跳过 ${report.skipped} 行）",
                reason = "no usable channels",
                session = session,
                failure = AppError.parse(EventCodes.SRC_PARSE_FAIL),
                alreadyLoggedParse = true,
            )
        }

        val record = try {
            val written = ImportRecord(
                name = name,
                sourceId = sourceId,
                copiedPath = lastImport.copyPathFor(name),
                sizeBytes = bytes.size.toLong(),
                importedAtMs = clock.nowMs(),
                formatLabel = report.format.label,
                channels = report.channels,
                streams = report.streams,
                sourceUri = sourceUri,
            )
            lastImport.write(written, bytes)
        } catch (e: Exception) {
            return failed(
                code = EventCodes.DB_FAIL,
                name = name,
                message = "无法保存导入的文件（存储空间或权限问题）：$name",
                reason = "copy failed",
                session = session,
                failure = AppError.storage(EventCodes.DB_FAIL, e),
                error = e,
            )
        }

        // Only now does the screen change: the list the user is looking at is replaced by the import.
        catalog.commit(prepared)
        logParsed(name, report, session, startedAt)
        logger.i(
            LogCategory.SOURCE,
            EventCodes.SRC_DEDUPE,
            "local playlist normalized",
            mapOf(
                "provider" to sourceId,
                "raw" to report.streamDedupe.raw,
                "unique" to report.streamDedupe.unique,
                "channels" to report.channels,
                "streams" to report.streams,
                "copied" to record.copiedPath,
                "sourceUri" to sourceUri,
                "session" to session,
            ),
        )

        return ImportResult.Done(
            ImportReport(
                name = name,
                sourceId = sourceId,
                formatLabel = report.format.label,
                rawEntries = report.rawEntries,
                skipped = report.skipped,
                channels = report.channels,
                streams = report.streams,
                elapsedMs = clock.nowMs() - startedAt,
                copiedPath = record.copiedPath,
            ),
        )
    }

    override suspend fun lastImported(): ImportedPlaylist? = withContext(dispatchers.io) {
        lastImport.record()?.let {
            ImportedPlaylist(
                name = it.name,
                sourceId = it.sourceId,
                copiedPath = it.copiedPath,
                sizeBytes = it.sizeBytes,
                importedAtMs = it.importedAtMs,
                sourceUri = it.sourceUri,
            )
        }
    }

    /** `SRC_PARSE_OK` with the numbers the parse produced (same fields the fixture loader logs). */
    private fun logParsed(
        name: String,
        report: CatalogLoadReport,
        session: String,
        startedAt: Long,
    ) {
        logger.i(
            LogCategory.SOURCE,
            EventCodes.SRC_PARSE_OK,
            "local playlist parsed",
            mapOf(
                "provider" to report.sourceId,
                "file" to name,
                "format" to report.format.label,
                "entries" to report.rawEntries,
                "skipped" to report.skipped,
                "lines" to report.lines,
                "ms" to (clock.nowMs() - startedAt),
                "session" to session,
            ),
        )
    }

    /** The picker's name for [uri], or the uri's last segment when it does not expose one. */
    private fun displayNameOf(uri: String): String {
        val fromPicker = runCatching { documents.displayName(uri) }.getOrNull()
        val candidate = fromPicker?.trim().orEmpty()
        if (candidate.isNotEmpty()) return candidate
        // Some pickers hand back the whole path encoded in the last segment, so un-escape before
        // taking the file name; a uri that carries no name at all gets a readable placeholder.
        val decoded = uri.replace("%2F", "/").replace("%2f", "/")
        return decoded.substringAfterLast('/').ifBlank { FALLBACK_URI_NAME }
    }

    private fun failed(
        code: String,
        name: String,
        message: String,
        reason: String,
        session: String = "",
        failure: AppError,
        error: Throwable? = null,
        alreadyLoggedParse: Boolean = false,
    ): ImportResult.Failed {
        if (!alreadyLoggedParse) {
            logger.w(
                category = LogCategory.SOURCE,
                code = code,
                message = "local playlist import failed",
                fields = mapOf(
                    "provider" to sourceIdFor(name),
                    "file" to name,
                    "reason" to reason,
                    "failure" to failure.failure.name,
                    "session" to session,
                ),
                error = error,
            )
        }
        return ImportResult.Failed(code = code, message = message)
    }

    /** The `channel.source_id` every row of this import carries. */
    private fun sourceIdFor(name: String): String = "local:${name.substringAfterLast('/')}"

    private fun isPlaylistName(name: String): Boolean {
        val lower = name.lowercase()
        return PLAYLIST_SUFFIXES.any { lower.endsWith(it) }
    }

    private companion object {
        /** Only list files that can plausibly be a playlist; anything else is noise in the picker. */
        val PLAYLIST_SUFFIXES = listOf(".m3u", ".m3u8", ".txt")
        const val MEGABYTE = 1024 * 1024
        /** Used only when a picked document exposes neither a display name nor a path segment. */
        const val FALLBACK_URI_NAME = "picked-playlist.m3u"
    }
}
