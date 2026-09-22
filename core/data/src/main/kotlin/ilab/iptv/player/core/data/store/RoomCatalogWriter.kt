package ilab.iptv.player.core.data.store

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.mapper.MappedCatalog
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.StreamDao
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes one parsed catalog into Room (docs/02 §6.1's last stage, now durable).
 *
 * It is the production [CatalogSink]: the fixture seed, the local import and (later) a P2-4 refresh
 * all reach Room through `ChannelCatalog.commit()` → this class, so "the list reads Room" and "the
 * write goes to Room" are one decision — that is the 收口 of the P2-1 × P2-6 integration conflict
 * (god's 2026-09-22 ruling).
 *
 * Four rules it exists to enforce:
 * - **batch size 500 rows per transaction** (docs/02 §4.5 C4). A 5k-stream list is ten transactions,
 *   not five thousand, and never one giant transaction that holds a write lock while the list is
 *   being painted.
 * - **ids come from the database, not the parser.** Channels are upserted on `(name_key, group_key)`
 *   and streams on `(channel_id, url_hash)`, so a refresh keeps the row ids — and therefore the
 *   favourites, sort order and `play_history` rows that point at them. The domain ids produced by
 *   `ChannelMapper` for one in-memory load are *not* used as database ids.
 * - **replace, not merge** (the [CatalogSink] contract). After the upsert, the channels that are not
 *   part of this catalog are deleted **and** the streams of the channels that *are* kept are trimmed
 *   to the incoming set, so an import does not leave the previous playlist behind — the same
 *   semantics the memory path's `replaceAll` has. Channel pruning leans on `stream.channel_id ->
 *   channel.id ON DELETE CASCADE`; the stream trim is a separate step because a channel survives on
 *   its `(name_key, group_key)` **identity**, and keeping the row would otherwise keep every stream
 *   the previous playlist had hung on it (卡 BUG-STALE-STREAM: 8 频道 / 10 流 的清单导入后界面显示
 *   「流 22」，同一行 3–4 路流——旧清单同名频道下的死源成了备胎).
 * - **no partial catalog.** The channel→id resolution and its streams are written per batch inside a
 *   transaction, so a failure leaves the previously stored catalog intact (docs/02 §11: a failed write
 *   must not destroy existing data).
 */
@Singleton
class RoomCatalogWriter @Inject constructor(
    private val database: IptvDatabase,
    private val channelDao: ChannelDao,
    private val streamDao: StreamDao,
    private val logger: Logger,
) : CatalogSink {

    /** Writes [catalog] (replace semantics) and returns how many rows (channels + streams) were touched. */
    override suspend fun write(catalog: MappedCatalog, nowMs: Long): Int {
        val databaseId = HashMap<Long, Long>(catalog.channels.size)
        var channelsWritten = 0

        catalog.channels.chunked(BATCH_SIZE).forEach { batch ->
            val ids = database.withTransaction {
                channelDao.upsertAll(batch.map { PersistenceMapper.toEntity(it, nowMs) })
            }
            batch.forEachIndexed { index, channel ->
                // `upsertAll` returns one id per input row, in order.
                databaseId[channel.id] = ids[index]
            }
            channelsWritten += batch.size
        }

        // The new stream set, keyed by *database* channel id: the stream trim below deletes exactly
        // the stored rows whose `(channel_id, url_hash)` key is not in here. A kept channel with no
        // stream in this catalog gets no entry, which is what makes its stored streams stale.
        val keptHashes = HashMap<Long, MutableSet<String>>(catalog.channels.size)

        var streamsWritten = 0
        // Drop streams whose channel was not part of this write: a stream without its channel would
        // trip the foreign key, and silently attaching it to another channel would be worse.
        val mapped = catalog.streams.mapNotNull { stream ->
            val channelId = databaseId[stream.channelId] ?: return@mapNotNull null
            val entity = PersistenceMapper.toEntity(stream.copy(channelId = channelId))
            keptHashes.getOrPut(channelId) { HashSet() } += entity.urlHash
            entity
        }
        mapped.chunked(BATCH_SIZE).forEach { batch ->
            streamsWritten += database.withTransaction { streamDao.upsertAll(batch) }
        }

        // Replace semantics (the [CatalogSink] contract): everything not in this catalog is gone.
        // Without this, an import would merge with the 658-channel fixture instead of replacing it —
        // exactly the "the list shows channels that are not in my playlist" surprise the P2-6 record
        // (docs/05-过程记录/19 §2) chose "导入即替换" to avoid. The cascade removes their streams.
        val removedChannels = pruneChannelsNotIn(databaseId.values.toHashSet())

        // …and the channels that *are* kept are replaced too, stream set included. A channel row
        // survives on its `(name_key, group_key)` identity so the user keeps it (favourites, hidden
        // flag, sort order, channel number, EPG binding — `ChannelDao.upsertAll`'s column split), but
        // the streams hanging on it belong to the *playlist*, not to the user: leaving the previous
        // playlist's rows there is the bug this step closes (same-name channel → its stale URL stays
        // as a fail-over candidate, and the screen's 流 count is bigger than the playlist).
        val removedStreams = pruneStreamsNotIn(keptHashes, databaseId.values.toHashSet())

        logger.i(
            category = LogCategory.SOURCE,
            code = EventCodes.DB_UPSERT,
            message = "catalog persisted",
            fields = mapOf(
                "channels" to channelsWritten,
                "streams" to streamsWritten,
                "removed" to removedChannels,
                "streamsRemoved" to removedStreams,
                "dropped" to (catalog.streams.size - mapped.size),
                "batch" to BATCH_SIZE,
            ),
        )
        return channelsWritten + streamsWritten
    }

    /**
     * The refresh half of the seam (卡 REFRESH-PERSIST-1): upsert the catalog's channels — additively,
     * keyed on `(name_key, group_key)` so an existing row keeps its id and its user-owned columns —
     * and hand back `in-memory channel id -> stored channel id`.
     *
     * This is the piece the refresh pipeline was missing: `ChannelMapper` mints channel ids per load,
     * and the pipeline wrote `stream` rows against those ids, so every row tripped the
     * `stream.channel_id -> channel.id` foreign key and the whole batch rolled back
     * (`DB_FAIL FOREIGN KEY constraint failed … written:0`). Writing the channels first and pointing
     * the streams at the ids the store returned fixes it, without the replace semantics of [write] —
     * a refresh must not delete the channels the user is looking at (they came from the last import /
     * the bundled snapshot), only make sure the ones it is about to attach streams to exist.
     *
     * A storage failure degrades (docs/02 §11): it returns an empty map and logs `DB_FAIL` rather than
     * throwing, so the caller drops the streams it cannot legally write and the previous catalog stands.
     */
    override suspend fun upsertChannels(catalog: MappedCatalog, nowMs: Long): Map<Long, Long> {
        if (catalog.channels.isEmpty()) return emptyMap()
        val ids = HashMap<Long, Long>(catalog.channels.size)
        try {
            catalog.channels.chunked(BATCH_SIZE).forEach { batch ->
                val stored = database.withTransaction {
                    channelDao.upsertAll(batch.map { PersistenceMapper.toEntity(it, nowMs) })
                }
                batch.forEachIndexed { index, channel ->
                    // `upsertAll` returns one id per input row, in order.
                    ids[channel.id] = stored[index]
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(
                category = LogCategory.APP,
                code = EventCodes.DB_FAIL,
                message = "channel upsert failed",
                fields = mapOf("channels" to catalog.channels.size, "written" to ids.size, "err" to e.message),
                error = e,
            )
            return emptyMap()
        }
        logger.d(
            category = LogCategory.SOURCE,
            code = EventCodes.DB_UPSERT,
            message = "refresh channels persisted",
            fields = mapOf("table" to "channel", "rows" to ids.size, "batch" to BATCH_SIZE),
        )
        return ids
    }

    /** Deletes every stored channel whose id is not in [kept]; returns how many channels were removed. */
    private suspend fun pruneChannelsNotIn(kept: Set<Long>): Int {
        val stale = channelDao.allIds().filterNot { it in kept }
        if (stale.isEmpty()) return 0
        var removed = 0
        // SQLite caps a statement at 999 bound parameters, so the `IN (...)` list is chunked (the
        // programme pruning does the same, docs/02 §5.1). One chunk = one transaction.
        stale.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
            removed += database.withTransaction { channelDao.deleteByIds(chunk) }
        }
        return removed
    }

    /**
     * Deletes every stored stream that this catalog does not carry: a stream of a kept [keptChannels]
     * channel whose `url_hash` is absent from [keptHashes] for that channel. Streams of channels that
     * were themselves pruned are already gone (the foreign key cascade), so they are not counted here.
     *
     * The diff is computed in Kotlin ([ChannelDao.allIds] style, one read + chunked deletes) rather
     * than SQL, because `url_hash NOT IN (...)` would carry one bound parameter per stream of a
     * channel and SQLite's ceiling is 999. Returns how many streams were removed.
     */
    private suspend fun pruneStreamsNotIn(
        keptHashes: Map<Long, Set<String>>,
        keptChannels: Set<Long>,
    ): Int {
        val stale = streamDao.allIdentities()
            .filter { it.channelId in keptChannels && it.urlHash !in keptHashes[it.channelId].orEmpty() }
            .map { it.id }
        if (stale.isEmpty()) return 0
        var removed = 0
        stale.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
            removed += database.withTransaction { streamDao.deleteByIds(chunk) }
        }
        return removed
    }

    companion object {
        /** docs/02 §4.5 C4: `PipelineLimits.batchSize = 500` rows per transaction. */
        const val BATCH_SIZE = 500

        /** Below SQLite's 999-parameter ceiling for `DELETE ... WHERE id IN (...)`. */
        const val DELETE_BATCH_SIZE = 400
    }
}
