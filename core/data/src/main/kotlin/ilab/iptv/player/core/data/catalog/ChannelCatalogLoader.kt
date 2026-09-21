package ilab.iptv.player.core.data.catalog

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.playlist.RememberedPlaylistSource
import ilab.iptv.player.core.common.DispatcherProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the playlist the app should open with, exactly once per process: **the remembered local
 * import if there is one, otherwise the bundled P1-2 fixture**.
 *
 * **TEST-ONLY in production (god's 2026-09-22 收口 ruling).** This is the *in-memory* recovery path:
 * it re-reads the kept import copy and republishes it into the process-wide store on every start.
 * Production no longer needs it — the catalog is durable in Room, so a restart reads SQLite and never
 * re-parses anything (`RoomCatalogSeeder`). It is kept, un-annotated so Hilt can never wire it, to
 * pin the memory path's own recovery semantics in `ChannelCatalogLoaderTest`, and as the reference
 * for the fixture-vs-import precedence the Room path replaces with durability. The trade-off is
 * written down in `docs/05-过程记录/23-持久化集成收口.md`.
 *
 * `ensureLoaded()` is idempotent and concurrency-safe: the browse screen can call it from several
 * coroutines (and after a rotation) without parsing the list twice.
 *
 * The fixture is a **fixture**, not a source: P1-2 is "load a playlist → channel list" with no
 * network and no database, and `tools/fixtures/README.md` explains why the real 658-channel baseline
 * is not vendored (third-party hosts and token URLs in a public repo). It is the right thing to show
 * on a fresh install — and the wrong thing to show a user who imported their own list, which is why
 * the import wins and why a *broken* remembered import falls back here instead of leaving the app
 * empty.
 *
 * Loading runs on the injected [DispatcherProvider.io] because it reads an asset; the parse itself is
 * ~150 KB of text and is measured in the report rather than assumed to be free.
 */
class ChannelCatalogLoader(
    private val dispatchers: DispatcherProvider,
    private val bundled: BundledPlaylist,
    private val remembered: RememberedPlaylistSource,
    private val catalog: ChannelCatalog,
    private val logger: Logger,
) : CatalogBootstrapper {

    private val mutex = Mutex()
    private val stateLock = Any()

    private var loaded = false
    private var lastReport: CatalogLoadReport? = null
    private var lastFailure: Throwable? = null

    /** Parses the bundled fixture into the store on first call; every later call is a no-op. */
    override suspend fun ensureLoaded(): CatalogLoadReport? {
        synchronized(stateLock) {
            if (loaded && lastReport != null) return lastReport
            if (loaded && lastFailure != null) return null
        }
        mutex.withLock {
            synchronized(stateLock) {
                if (loaded && lastReport != null) return lastReport
                if (loaded && lastFailure != null) return null
            }
            return try {
                val restored = withContext(dispatchers.io) { remembered.read() }
                val report = if (restored != null) {
                    loadRemembered(restored.bytes, restored.sourceId, restored.name)
                        // A remembered import that cannot be parsed must not cost the user the app:
                        // fall back to the fixture and keep the (broken) copy on disk for diagnosis.
                        ?: loadBundled()
                } else {
                    loadBundled()
                }
                if (report == null) {
                    synchronized(stateLock) {
                        loaded = true
                        lastFailure = IllegalStateException("no playlist could be loaded")
                    }
                    null
                } else {
                    logSuccess(report)
                    synchronized(stateLock) {
                        loaded = true
                        lastReport = report
                        lastFailure = null
                    }
                    report
                }
            } catch (e: Exception) {
                logFailure(e)
                synchronized(stateLock) {
                    loaded = true
                    lastFailure = e
                }
                null
            }
        }
    }

    /** The report of the load that happened, or null before the first load / after a failure. */
    fun report(): CatalogLoadReport? = catalog.lastReport()

    /** Parses a remembered import; null (after logging) when the copy no longer parses. */
    private suspend fun loadRemembered(bytes: ByteArray, sourceId: String, name: String): CatalogLoadReport? =
        when (val result = catalog.prepare(bytes, sourceId)) {
            is AppResult.Ok -> if (result.value.report.channels == 0) {
                // A truncated or foreign file can parse "successfully" into nothing. Publishing that
                // would leave the user with an empty list and no way back, so it is treated as
                // unreadable and the fixture takes over (the copy stays on disk for diagnosis).
                logger.w(
                    LogCategory.SOURCE,
                    EventCodes.SRC_PARSE_FAIL,
                    "remembered import has no channels, using fixture",
                    mapOf("provider" to sourceId, "file" to name, "lines" to result.value.report.lines),
                )
                null
            } else {
                logger.i(
                    LogCategory.SOURCE,
                    EventCodes.SRC_PARSE_OK,
                    "remembered import restored",
                    mapOf(
                        "provider" to sourceId,
                        "file" to name,
                        "entries" to result.value.report.rawEntries,
                        "channels" to result.value.report.channels,
                    ),
                )
                catalog.commit(result.value)
            }

            is AppResult.Err -> {
                logger.w(
                    LogCategory.SOURCE,
                    EventCodes.SRC_PARSE_FAIL,
                    "remembered import unreadable, using fixture",
                    mapOf(
                        "provider" to sourceId,
                        "file" to name,
                        "failure" to result.error.failure.name,
                    ),
                    result.error.cause,
                )
                null
            }
        }

    /** Reads and parses the bundled fixture; null when even that fails (there is no further fallback). */
    private suspend fun loadBundled(): CatalogLoadReport? = when (
        val result = catalog.prepare(bundled.read(), bundled.sourceId)
    ) {
        is AppResult.Ok -> catalog.commit(result.value)
        is AppResult.Err -> {
            logFailure(result.error.cause ?: IllegalStateException("bundled playlist rejected"))
            null
        }
    }

    private fun logSuccess(report: CatalogLoadReport) {
        logger.i(
            category = LogCategory.SOURCE,
            code = EventCodes.SRC_PARSE_OK,
            message = "catalog parsed",
            fields = mapOf(
                "provider" to report.sourceId,
                "format" to report.format.label,
                "entries" to report.rawEntries,
                "skipped" to report.skipped,
                "lines" to report.lines,
                "ms" to report.elapsedMs,
            ),
        )
        logger.i(
            category = LogCategory.SOURCE,
            code = EventCodes.SRC_DEDUPE,
            message = "fixture normalized",
            fields = mapOf(
                "raw" to report.streamDedupe.raw,
                "unique" to report.streamDedupe.unique,
                "channels" to report.channels,
                "streams" to report.streams,
                "perSource" to report.streamDedupe.perSource,
            ),
        )
    }

    private fun logFailure(error: Throwable) {
        logger.w(
            category = LogCategory.SOURCE,
            code = EventCodes.SRC_FETCH_FAIL,
            message = "catalog load failed",
            fields = mapOf("provider" to bundled.sourceId, "err" to error.message),
            error = error,
        )
    }
}
