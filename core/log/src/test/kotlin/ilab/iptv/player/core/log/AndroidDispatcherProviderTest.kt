package ilab.iptv.player.core.log

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The default binding's two promises (docs/02 §4.1/§10): the shared pools are the kotlinx ones, and
 * `single` really is **one** thread — a serialized-writer dispatcher that secretly ran on a pool
 * would be worse than no binding at all, because every caller would keep assuming it is serial.
 *
 * `main` and `engine` are deliberately not touched here: `main` needs a real Looper and `engine`
 * needs a `HandlerThread`, neither of which exists in a plain JVM unit test — that is exactly why
 * both are `by lazy` in [AndroidDispatcherProvider]. `:app:assembleDebug` is what proves the Hilt
 * graph can build the whole object.
 */
class AndroidDispatcherProviderTest {

    @Test
    fun `io and default are the shared kotlinx dispatchers`() {
        val provider = AndroidDispatcherProvider()

        assertThat(provider.io).isSameInstanceAs(Dispatchers.IO)
        assertThat(provider.default).isSameInstanceAs(Dispatchers.Default)
    }

    @Test
    fun `single owns one dedicated thread`() = runBlocking {
        val provider = AndroidDispatcherProvider()

        // Compare the threads themselves, not their names: kotlinx appends `@coroutine#N` to the
        // name while a coroutine runs, so the name is a per-coroutine label on one real thread.
        val threads = (1..6).map { async(provider.single) { Thread.currentThread() } }.awaitAll()

        assertThat(threads.toSet()).hasSize(1)
        assertThat(threads.first().name).startsWith("iptv-single")
        assertThat(threads.first().name).doesNotContain("DefaultDispatcher")
    }

    @Test
    fun `single never runs two writers at once`() = runBlocking {
        val provider = AndroidDispatcherProvider()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        (1..8).map {
            async(provider.single) {
                val now = inFlight.incrementAndGet()
                peak.accumulateAndGet(now, ::maxOf)
                Thread.sleep(5)
                inFlight.decrementAndGet()
            }
        }.awaitAll()

        assertThat(peak.get()).isEqualTo(1)
    }
}
