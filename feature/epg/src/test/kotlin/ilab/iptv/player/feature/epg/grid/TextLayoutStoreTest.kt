package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TextLayoutStoreTest {

    private val factory = StubTextFactory()

    @Test
    fun `the same key is laid out once`() {
        val store = TextLayoutStore(factory)
        store.get("News", 100, GridTextStyle.BLOCK_TITLE)
        store.get("News", 100, GridTextStyle.BLOCK_TITLE)
        assertThat(factory.created).isEqualTo(1)
        assertThat(store.hits).isEqualTo(1)
        assertThat(store.misses).isEqualTo(1)
        assertThat(store.hitRate()).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `text, width and style are all part of the key`() {
        val store = TextLayoutStore(factory)
        store.get("News", 100, GridTextStyle.BLOCK_TITLE)
        store.get("News", 120, GridTextStyle.BLOCK_TITLE)
        store.get("News", 100, GridTextStyle.BLOCK_TIME)
        assertThat(factory.created).isEqualTo(3)
        assertThat(store.size).isEqualTo(3)
    }

    @Test
    fun `the cache never grows past the frozen 512 entries`() {
        val store = TextLayoutStore(factory)
        for (index in 0 until 700) {
            store.get("Block $index", 100 + index, GridTextStyle.BLOCK_TITLE)
        }
        assertThat(store.size).isEqualTo(TextLayoutStore.DEFAULT_MAX_ENTRIES)
        assertThat(factory.created).isEqualTo(700)
    }

    @Test
    fun `eviction is least-recently-used, so a revisited block is a hit`() {
        val store = TextLayoutStore(factory, maxEntries = 2)
        store.get("A", 10, GridTextStyle.BLOCK_TITLE)
        store.get("B", 10, GridTextStyle.BLOCK_TITLE)
        store.get("A", 10, GridTextStyle.BLOCK_TITLE) // A becomes the most recent
        store.get("C", 10, GridTextStyle.BLOCK_TITLE) // evicts B
        assertThat(store.size).isEqualTo(2)
        val hitsBefore = store.hits
        store.get("A", 10, GridTextStyle.BLOCK_TITLE)
        assertThat(store.hits).isEqualTo(hitsBefore + 1)
        val missesBefore = store.misses
        store.get("B", 10, GridTextStyle.BLOCK_TITLE)
        assertThat(store.misses).isEqualTo(missesBefore + 1)
    }

    @Test
    fun `hit rate is perfect before anything was asked`() {
        val store = TextLayoutStore(factory)
        assertThat(store.hitRate()).isEqualTo(1.0)
        store.resetCounters()
        assertThat(store.hits).isEqualTo(0)
        assertThat(store.misses).isEqualTo(0)
    }

    @Test
    fun `a non-positive cache size is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { TextLayoutStore(factory, maxEntries = 0) }
    }
}
