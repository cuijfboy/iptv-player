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
 * Three rules it exists to enforce:
 * - **batch size 500 rows per transaction** (docs/02 §4.5 C4). A 5k-stream list is ten transactions,
 *   not five thousand, and never one giant transaction that holds a write lock while the list is
 *   being painted.
 * - **ids come from the database, not the parser.** Channels are upserted on `(name_key, group_key)`
 *   and streams on `(channel_id, url_hash)`, so a refresh keeps the row ids — and therefore the
 *   favourites, sort order and `play_history` rows that point at them. The domain ids produced by
 *   `ChannelMapper` for one in-memory load are *not* used as database ids.
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
) {

    /** Writes [catalog] and returns how many rows (channels + streams) were touched. */
    suspend fun write(catalog: MappedCatalog, nowMs: Long): Int {
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

        var streamsWritten = 0
        // Drop streams whose channel was not part of this write: a stream without its channel would
        // trip the foreign key, and silently attaching it to another channel would be worse.
        val mapped = catalog.streams.mapNotNull { stream ->
            val channelId = databaseId[stream.channelId] ?: return@mapNotNull null
            PersistenceMapper.toEntity(stream.copy(channelId = channelId))
        }
        mapped.chunked(BATCH_SIZE).forEach { batch ->
            streamsWritten += database.withTransaction { streamDao.upsertAll(batch) }
        }

        logger.i(
            category = LogCategory.SOURCE,
            code = EventCodes.DB_UPSERT,
            message = "catalog persisted",
            fields = mapOf(
                "channels" to channelsWritten,
                "streams" to streamsWritten,
                "dropped" to (catalog.streams.size - mapped.size),
                "batch" to BATCH_SIZE,
            ),
        )
        return channelsWritten + streamsWritten
    }

    companion object {
        /** docs/02 §4.5 C4: `PipelineLimits.batchSize = 500` rows per transaction. */
        const val BATCH_SIZE = 500
    }
}
