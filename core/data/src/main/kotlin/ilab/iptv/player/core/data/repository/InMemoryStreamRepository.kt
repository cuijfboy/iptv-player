package ilab.iptv.player.core.data.repository

import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth
import ilab.iptv.player.core.model.StreamOutcome

/**
 * docs/02 §4.3 `StreamRepository` over the same in-memory [ChannelStore].
 *
 * **TEST-ONLY in production** (god's 2026-09-22 收口 ruling): the production binding is
 * `RoomStreamRepository`. Not Hilt-annotated on purpose, so it cannot be wired by accident.
 *
 * P1-2 needs the read side (`candidates`) to order a channel's streams and the write side
 * (`upsertAll`) to prove the `UNIQUE(channel_id, url_hash)` upsert shape works before Room exists.
 * Health lives in a small in-process map here and moves into the `play_history` / health columns in
 * P2; nothing in this class pretends to be durable.
 */
class InMemoryStreamRepository(
    private val store: ChannelStore,
) : StreamRepository {

    private val health = HashMap<Long, MutableHealth>()

    override suspend fun candidates(channelId: Long): List<Stream> =
        orderStreams(store.snapshotStreams().filter { it.channelId == channelId })

    override suspend fun upsertAll(streams: List<Stream>) {
        store.upsertStreams(streams)
    }

    override suspend fun recordOutcome(streamId: Long, outcome: StreamOutcome) {
        val current = store.snapshotStreams().firstOrNull { it.id == streamId } ?: return
        val updated = current.copy(
            lastOkAtMs = if (outcome.ok) outcome.atMs else current.lastOkAtMs,
            lastCheckAtMs = outcome.atMs,
            failCount = if (outcome.ok) 0 else current.failCount + 1,
            lastError = if (outcome.ok) null else (outcome.failure?.name ?: outcome.detail),
        )
        store.upsertStreams(listOf(updated))
        val entry = health.getOrPut(streamId) { MutableHealth() }
        entry.attempts += 1
        if (outcome.ok) {
            entry.failures = 0
            entry.lastOkAtMs = outcome.atMs
        } else {
            entry.failures += 1
        }
    }

    override suspend fun health(streamId: Long): StreamHealth {
        val entry = health[streamId] ?: MutableHealth()
        return StreamHealth(
            attempts = entry.attempts,
            failures = entry.failures,
            lastOkAtMs = entry.lastOkAtMs,
            consecutiveFails = entry.failures,
        )
    }

    /**
     * Clears the `last_check_at` stamp of every stream last checked before [beforeMs] — the
     * "this is out of date, re-probe it" signal — and returns how many were cleared. Streams that
     * were never checked stay as they are: they are not stale, they are unknown.
     */
    override suspend fun markStale(beforeMs: Long): Int {
        val stale = store.snapshotStreams().filter { (it.lastCheckAtMs ?: Long.MAX_VALUE) < beforeMs }
        if (stale.isEmpty()) return 0
        store.upsertStreams(stale.map { it.copy(lastCheckAtMs = null) })
        health.clear()
        return stale.size
    }

    private class MutableHealth {
        var attempts: Int = 0
        var failures: Int = 0
        var lastOkAtMs: Long? = null
    }
}
