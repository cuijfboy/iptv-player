package ilab.iptv.player.core.data.store

import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-wide channel/stream state the P1-2 repositories read and write (docs/02 §5.2's
 * `Domain Model` box). It is the stand-in for Room until P2-1: same single-writer discipline,
 * `StateFlow` instead of SQLite.
 *
 * Both flows hold immutable snapshots, so a reader never observes a half-applied change; every
 * mutation goes through `update {}` (compare-and-set), which is safe when two coroutines write at
 * once. Nothing here blocks, which is what docs/02 §4.5 C6 asks of a state holder.
 */
@Singleton
class ChannelStore @Inject constructor() {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _streams = MutableStateFlow<List<Stream>>(emptyList())

    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

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
