package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The frozen 2 s digit window of docs/02 §8.2, pinned without a TV: what "1", "1"+"2" and "1"+"2"+"3"
 * mean, and when the buffer commits.
 */
class ChannelNumberBufferTest {

    private val buffer = ChannelNumberBuffer()

    @Test
    fun `a single digit waits for the window and then commits`() {
        val pending = buffer.onDigit(7, nowMs = 0)
        assertThat(pending).isInstanceOf(ChannelNumberBuffer.Decision.Pending::class.java)
        assertThat((pending as ChannelNumberBuffer.Decision.Pending).number).isEqualTo(7)

        assertThat(buffer.onTick(ChannelNumberBuffer.WINDOW_MS - 1)).isInstanceOf(
            ChannelNumberBuffer.Decision.Pending::class.java,
        )
        val commit = buffer.onTick(ChannelNumberBuffer.WINDOW_MS)
        assertThat(commit).isEqualTo(ChannelNumberBuffer.Decision.Commit(7))
    }

    @Test
    fun `a second digit extends the number and restarts the window`() {
        buffer.onDigit(1, nowMs = 0)
        val pending = buffer.onDigit(2, nowMs = 500)

        assertThat((pending as ChannelNumberBuffer.Decision.Pending).number).isEqualTo(12)
        assertThat(pending.remainingMs).isEqualTo(ChannelNumberBuffer.WINDOW_MS)
        assertThat(buffer.onTick(500 + ChannelNumberBuffer.WINDOW_MS - 1)).isInstanceOf(
            ChannelNumberBuffer.Decision.Pending::class.java,
        )
        assertThat(buffer.onTick(500 + ChannelNumberBuffer.WINDOW_MS))
            .isEqualTo(ChannelNumberBuffer.Decision.Commit(12))
    }

    @Test
    fun `a leading zero is just a digit, so 01 commits as channel 1`() {
        buffer.onDigit(0, nowMs = 0)
        buffer.onDigit(1, nowMs = 100)

        // The window restarts with the second digit, so the commit is 2 s after it, not after the first.
        assertThat(buffer.onTick(100 + ChannelNumberBuffer.WINDOW_MS))
            .isEqualTo(ChannelNumberBuffer.Decision.Commit(1))
    }

    @Test
    fun `the third digit commits immediately`() {
        buffer.onDigit(1, nowMs = 0)
        buffer.onDigit(0, nowMs = 10)

        assertThat(buffer.onDigit(0, nowMs = 20)).isEqualTo(ChannelNumberBuffer.Decision.Commit(100))
        assertThat(buffer.pendingDigits).isEmpty()
    }

    @Test
    fun `an empty buffer never commits anything`() {
        assertThat(buffer.onTick(nowMs = 5_000)).isEqualTo(ChannelNumberBuffer.Decision.None)
        buffer.onDigit(1, nowMs = 0)
        buffer.reset()
        assertThat(buffer.onTick(nowMs = 5_000)).isEqualTo(ChannelNumberBuffer.Decision.None)
    }
}
