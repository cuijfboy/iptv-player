package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import org.junit.Test

class MemoryRingSinkTest {

    @Test
    fun `keeps only the newest capacity events and counts the evictions`() {
        val sink = MemoryRingSink(capacity = 3)

        (1..5).forEach { sink.write(event(it.toLong())) }

        assertThat(sink.size).isEqualTo(3)
        assertThat(sink.evictedCount).isEqualTo(2)
        assertThat(sink.snapshot().map { it.seq }).containsExactly(3L, 4L, 5L).inOrder()
    }

    @Test
    fun `tail returns the newest n events oldest first`() {
        val sink = MemoryRingSink(capacity = 5)
        (1..5).forEach { sink.write(event(it.toLong())) }

        assertThat(sink.tail(2).map { it.seq }).containsExactly(4L, 5L).inOrder()
        assertThat(sink.tail(99).map { it.seq }).hasSize(5)
        assertThat(sink.tail(0)).isEmpty()
    }

    @Test
    fun `snapshot is a copy and stays valid while writes continue`() {
        val sink = MemoryRingSink(capacity = 2)
        sink.write(event(1))
        val snapshot = sink.snapshot()

        sink.write(event(2))
        sink.write(event(3))

        assertThat(snapshot.map { it.seq }).containsExactly(1L)
        assertThat(sink.snapshot().map { it.seq }).containsExactly(2L, 3L).inOrder()
    }

    @Test
    fun `clear empties the ring but keeps the eviction counter`() {
        val sink = MemoryRingSink(capacity = 1)
        sink.write(event(1))
        sink.write(event(2))

        sink.clear()

        assertThat(sink.size).isEqualTo(0)
        assertThat(sink.evictedCount).isEqualTo(1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non positive capacity`() {
        MemoryRingSink(capacity = 0)
    }

    @Test
    fun `id is stable for the hilt set`() {
        assertThat(MemoryRingSink().id).isEqualTo(MemoryRingSink.ID)
        assertThat(MemoryRingSink.DEFAULT_CAPACITY).isEqualTo(2000)
    }

    private fun event(seq: Long): LogEvent = LogEvent(
        seq = seq,
        ts = 1_700_000_000_000L + seq,
        elapsedMs = seq * 10,
        level = LogLevel.INFO,
        category = LogCategory.APP,
        code = "APP_START",
        message = "event $seq",
        thread = "test",
        sessionId = "app-0000",
    )
}
