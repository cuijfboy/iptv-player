package ilab.iptv.player.core.data.catalog

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.store.RoomCatalogWriter
import ilab.iptv.player.core.database.dao.ChannelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Cold start reads a catalog" — the P2-1 replacement for
 * [ChannelCatalogLoader]'s in-memory load.
 *
 * It seeds the database from the same bundled fixture exactly **once per database lifetime**: if the
 * `channel` table already has rows, nothing is parsed and nothing is written. On a cold start after
 * the first run the channel list therefore comes from SQLite (no asset read, no parse), which is what
 * "把内存态换成持久化" means in practice.
 *
 * The parse itself is the same pipeline the in-memory loader uses ([ChannelCatalog.parse]), so the
 * two paths cannot drift; only the sink differs. Seeding happens on [Dispatchers.IO] and inside
 * [RoomCatalogWriter]'s transactions, so it never touches the main thread or the playback thread
 * (docs/02 §4.5 C4/C6). A failure is logged as `DB_FAIL` and swallowed: the app degrades to "no
 * catalog yet" rather than crashing, and the next start retries (docs/02 §11).
 */
@Singleton
class RoomCatalogSeeder @Inject constructor(
    private val bundled: BundledPlaylist,
    private val catalog: ChannelCatalog,
    private val writer: RoomCatalogWriter,
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
                val nowMs = System.currentTimeMillis()
                // TESTABLE-1 split ChannelCatalog.parse() into prepare()/commit(): the seeder only
                // needs the mapped catalog, so it prepares and writes to Room itself (the in-memory
                // store path stays untouched).
                val prepared = withContext(Dispatchers.IO) {
                    val text = bundled.read().toString(Charsets.UTF_8)
                    catalog.prepare(text, bundled.sourceId)
                }
                writer.write(prepared.mapped, nowMs)
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
        /** The P1-2 bundled fixture; the same asset [ChannelCatalogLoader] reads. */
        internal const val FIXTURE_ASSET = "playlists/p1-2-baseline.m3u"
        internal const val FIXTURE_SOURCE_ID = "p1-2-fixture"
    }
}
