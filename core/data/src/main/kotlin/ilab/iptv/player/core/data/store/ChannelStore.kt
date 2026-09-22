package ilab.iptv.player.core.data.store

import ilab.iptv.player.core.data.mapper.MappedCatalog
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The process-wide channel/stream state the P1-2 repositories read and write (docs/02 §5.2's
 * `Domain Model` box). It is the stand-in for Room until P2-1: same single-writer discipline,
 * `StateFlow` instead of SQLite.
 *
 * Both flows hold immutable snapshots, so a reader never observes a half-applied change; every
 * mutation goes through `update {}` (compare-and-set), which is safe when two coroutines write at
 * once. Nothing here blocks, which is what docs/02 §4.5 C6 asks of a state holder.
 *
 * **TEST-ONLY in production (god's 2026-09-22 收口 ruling).** Since the write seam
 * ([CatalogSink]) landed, the production catalog lives in Room and the production `ChannelRepository`
 * / `StreamRepository` are the `Room*` implementations. This store, `InMemoryChannelRepository`,
 * `InMemoryStreamRepository` and `ChannelCatalogLoader` are the in-memory path kept for the
 * off-device tests: it is deliberately **not** Hilt-annotated any more so it can never be wired into
 * the app by accident. See `docs/05-过程记录/23-持久化集成收口.md` for the choice and its rationale.
 */
class ChannelStore : CatalogSink {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _streams = MutableStateFlow<List<Stream>>(emptyList())

    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

    /**
     * [CatalogSink] over the snapshot: a whole-catalog replace, exactly like [replaceAll]. The
     * timestamp is ignored — a `StateFlow` has no `updated_at` column to stamp.
     *
     * Same contract as [RoomCatalogWriter] (卡 BUG-STALE-STREAM): the incoming catalog is the whole
     * table, so a channel that survives by name does **not** keep the previous playlist's streams.
     * A snapshot replace gets that for free — the Room writer has to trim the surviving channel's
     * streams explicitly, because it upserts on `(name_key, group_key)` and keeps the row.
     *
     * The two sinks differ past the streams, and on purpose: a `StateFlow` has no ids to preserve, so
     * this one also drops the user-owned columns (favourite / hidden / sort order / channel number)
     * that the Room writer carries over from the stored row. It is the test double, not the
     * production path (see the class KDoc).
     */
    override suspend fun write(catalog: MappedCatalog, nowMs: Long): Int {
        replaceAll(catalog.channels, catalog.streams)
        return catalog.channels.size + catalog.streams.size
    }

    /** Replaces the whole catalog (one playlist load). Ids inside the two lists must be consistent. */
    fun replaceAll(channels: List<Channel>, streams: List<Stream>) {
        _channels.value = channels.toList()
        _streams.value = streams.toList()
    }

    /** Applies [transform] to one channel; returns false when the id is unknown. */
    fun updateChannel(id: Long, transform: (Channel) -> Channel): Boolean {
        var found = false
        _channels.update { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) {
                current
            } else {
                found = true
                current.toMutableList().also { it[index] = transform(it[index]) }
            }
        }
        return found
    }

    fun updateChannels(transform: (List<Channel>) -> List<Channel>) {
        _channels.update { transform(it) }
    }

    /** Insert-or-replace by `(channel_id, url_hash)` — the docs/02 §5.1 stream unique index. */
    fun upsertStreams(incoming: List<Stream>) {
        if (incoming.isEmpty()) return
        _streams.update { current ->
            val byKey = LinkedHashMap<Pair<Long, String>, Stream>(current.size + incoming.size)
            current.forEach { byKey[it.channelId to it.urlHash] = it }
            incoming.forEach { byKey[it.channelId to it.urlHash] = it }
            byKey.values.toList()
        }
    }

    fun snapshotStreams(): List<Stream> = _streams.value
}
