package ilab.iptv.player.core.data.repository

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.dao.StreamDao
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth
import ilab.iptv.player.core.model.StreamOutcome
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * docs/02 §4.3 `StreamRepository`, backed by Room (P2-1). Drop-in for [InMemoryStreamRepository].
 *
 * What changed from the in-memory version, and why:
 * - **Health is durable.** The in-memory implementation kept `attempts`/`failures` in a map that died
 *   with the process; here `recordOutcome` writes the §5.1 health columns *and* appends the
 *   `play_history` row that carries the attempt, so `health()` survives a restart.
 * - **`recordOutcome` is the writer of `play_history`.** One outcome = one row (`started_at`,
 *   `start_cost_ms`, `result`, `channel_id`, `stream_id` all come from the outcome and the stream).
 *   This is a hand-off point for P1-3/P1-4: the playback path must report through this port instead of
 *   inserting its own history rows, otherwise one attempt is counted twice.
 * - **Writes are batched at 500 rows per transaction** (docs/02 §4.5 C4) and never run on the caller's
 *   optional dispatcher: every DAO call is a `suspend` function, so Room performs it on its own query
 *   executor, which is not the playback thread (docs/02 §4.5 C2 vs C4).
 */
@Singleton
class RoomStreamRepository @Inject constructor(
    private val database: IptvDatabase,
    private val streamDao: StreamDao,
    private val logger: Logger,
) : StreamRepository {

    /** Candidates in the §4.3 order: score ↓, priority ↑, `last_ok_at` ↓ (nulls last), id ↑. */
    override suspend fun candidates(channelId: Long): List<Stream> =
        orderStreams(streamDao.candidates(channelId).map(PersistenceMapper::toDomain))

    override suspend fun upsertAll(streams: List<Stream>) {
        if (streams.isEmpty()) return
        var written = 0
        try {
            streams.chunked(BATCH_SIZE).forEach { batch ->
                written += database.withTransaction {
                    streamDao.upsertAll(batch.map(PersistenceMapper::toEntity))
                }
            }
        } catch (e: Exception) {
            // docs/02 §11: a storage failure degrades, it does not crash and it does not wipe state.
            logger.w(
                category = LogCategory.APP,
                code = EventCodes.DB_FAIL,
                message = "stream upsert failed",
                fields = mapOf("streams" to streams.size, "written" to written, "err" to e.message),
                error = e,
            )
            return
        }
        logger.d(
            category = LogCategory.APP,
            code = EventCodes.DB_UPSERT,
            message = "streams persisted",
            fields = mapOf("streams" to written, "batch" to BATCH_SIZE),
        )
    }

    override suspend fun recordOutcome(streamId: Long, outcome: StreamOutcome) {
        val stream = streamDao.getById(streamId) ?: return
        database.withTransaction {
            streamDao.recordOutcome(
                streamId = streamId,
                ok = outcome.ok,
                atMs = outcome.atMs,
                lastError = if (outcome.ok) null else outcome.failure?.name ?: outcome.detail,
            )
            // The attempt itself, so `health()` and the diagnostics page have a durable trail.
            database.playHistoryDao().insert(PersistenceMapper.toHistory(stream, outcome))
        }
        if (!outcome.ok && outcome.failure == FailureClass.STORAGE) {
            logger.w(
                category = LogCategory.APP,
                code = EventCodes.DB_FAIL,
                message = "storage failure recorded",
                fields = mapOf("stream" to streamId),
            )
        }
    }

    override suspend fun health(streamId: Long): StreamHealth {
        val counts = streamDao.healthCounts(streamId)
        return StreamHealth(
            attempts = counts?.attempts ?: 0,
            failures = counts?.failures ?: 0,
            lastOkAtMs = streamDao.lastOkAt(streamId),
            consecutiveFails = streamDao.consecutiveFails(streamId) ?: 0,
        )
    }

    override suspend fun markStale(beforeMs: Long): Int = streamDao.markStale(beforeMs)

    companion object {
        /** docs/02 §4.5 C4: `PipelineLimits.batchSize = 500` streams per transaction. */
        const val BATCH_SIZE = 500
    }
}
