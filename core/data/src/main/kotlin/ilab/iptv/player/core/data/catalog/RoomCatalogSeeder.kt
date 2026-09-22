package ilab.iptv.player.core.data.catalog

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.common.DispatcherProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Cold start reads a catalog" — the **production** first-fill for the Room path, and (since the
 * CatalogSink 收口) the one startup path the app uses. The in-memory [ChannelCatalogLoader] is kept
 * only for the off-device tests.
 *
 * It seeds the database from the injected [BundledPlaylist] exactly **once per database lifetime**: if
 * the `channel` table already has rows, nothing is parsed and nothing is written. On a cold start after
 * the first run — or after a local import — the channel table already has rows and this is a no-op:
 * the list comes from SQLite with no asset read and no parse. That is what makes the import durable
 * without a second "restore the remembered copy" step (the import wrote Room through the same sink).
 *
 * The parse and the write are the same pipeline and the same seam the in-memory loader and the local
 * import use ([ChannelCatalog.commit] → `CatalogSink`), so no writer can drift from another. Seeding
 * happens on [Dispatchers.IO] and inside the sink's transactions, so it never touches the main thread
 * or the playback thread
 * (docs/02 §4.5 C4/C6). A failure is logged as `DB_FAIL` and swallowed: the app degrades to "no
 * catalog yet" rather than crashing, and the next start retries (docs/02 §11).
 */
@Singleton
class RoomCatalogSeeder @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val bundled: BundledPlaylist,
    private val catalog: ChannelCatalog,
    private val channelDao: ChannelDao,
    private val logger: Logger,
) {

    private val mutex = Mutex()

    @Volatile
    private var seeded = false

    /** True when this call is the one that seeded the database. */
    suspend fun ensureSeeded(): Boolean {
        if (seeded) return false
        mutex.withLock {
            if (seeded) return false
            return try {
                if (channelDao.count() > 0) {
                    seeded = true
                    return false
                }
                // parse on IO, then publish through the one write seam (ChannelCatalog.commit →
                // the injected CatalogSink, which is RoomCatalogWriter in production). The seeder no
                // longer touches RoomCatalogWriter directly: that was the second write end the 收口
                // removed.
                val prepared = withContext(dispatchers.io) {
                    val text = bundled.read().toString(Charsets.UTF_8)
                    catalog.prepare(text, bundled.sourceId)
                }
                // SNAPSHOT-1: a bundled file that parses "successfully" into nothing (truncated
                // asset, a foreign file, a build that shipped no snapshot) must not be published as
                // the user's catalog — an empty list with the seed marked done is the one outcome
                // the user cannot recover from. Same guard, and same reason, as
                // `ChannelCatalogLoader.loadRemembered`. Degrading to "no catalog yet" keeps the
                // next start (or the wizard / a manual import) able to fill it.
                if (prepared.report.channels == 0) {
                    logger.w(
                        category = LogCategory.SOURCE,
                        code = EventCodes.SRC_PARSE_FAIL,
                        message = "bundled snapshot has no channels, not seeding",
                        fields = mapOf(
                            "provider" to bundled.sourceId,
                            "lines" to prepared.report.lines,
                            "entries" to prepared.report.rawEntries,
                        ),
                    )
                    return false
                }
                catalog.commit(prepared)
                logger.i(
                    category = LogCategory.SOURCE,
                    code = EventCodes.SRC_PARSE_OK,
                    message = "fixture seeded into Room",
                    fields = mapOf(
                        "provider" to prepared.report.sourceId,
                        "format" to prepared.report.format.label,
                        "entries" to prepared.report.rawEntries,
                        "channels" to prepared.report.channels,
                        "streams" to prepared.report.streams,
                        "ms" to prepared.report.elapsedMs,
                    ),
                )
                seeded = true
                true
            } catch (e: Exception) {
                logger.w(
                    category = LogCategory.SOURCE,
                    code = EventCodes.DB_FAIL,
                    message = "catalog seed failed",
                    fields = mapOf("asset" to bundled.sourceId, "err" to e.message),
                    error = e,
                )
                false
            }
        }
    }

    companion object {
        /**
         * The P1-2 bundled fixture. **Test-only since SNAPSHOT-1**: production seeds the TV-measured
         * snapshot instead ([AssetBundledPlaylist.SNAPSHOT_ASSET], bound in `DataModule`), while the
         * `:core:data` rigs still parse this synthetic file (`RoomFixtures`).
         */
        internal const val FIXTURE_ASSET = "playlists/p1-2-baseline.m3u"
        internal const val FIXTURE_SOURCE_ID = "p1-2-fixture"
    }
}
