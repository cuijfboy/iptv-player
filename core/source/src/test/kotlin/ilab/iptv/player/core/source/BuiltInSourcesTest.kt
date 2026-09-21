package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.provider.BuiltInSources
import org.junit.Test

class BuiltInSourcesTest {

    @Test
    fun `the catalogue is the 17 baseline aggregates`() {
        assertThat(BuiltInSources.CATALOG).hasSize(17)
    }

    @Test
    fun `ids are unique and lookups resolve`() {
        val ids = BuiltInSources.CATALOG.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
        for (id in ids) assertThat(BuiltInSources.byId(id)).isNotNull()
    }

    @Test
    fun `every source is an https aggregate list, not a channel URL`() {
        for (descriptor in BuiltInSources.CATALOG) {
            assertThat(descriptor.url).startsWith("https://")
            assertThat(descriptor.url).doesNotContain(".m3u8?")
        }
    }
}
