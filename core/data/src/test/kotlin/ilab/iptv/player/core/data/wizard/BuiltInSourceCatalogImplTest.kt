package ilab.iptv.player.core.data.wizard

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.provider.BuiltInSources
import org.junit.Test

/**
 * docs/04 P2-9 item 2 ①: "内置聚合源默认全开".
 *
 * The wizard's 选源 step says "内置聚合源 N 个，已全部启用". This pins what N is and that the list the
 * step shows is the catalogue the refresh pipeline actually fetches — the two drifting apart would
 * make the wizard's one factual claim wrong.
 */
class BuiltInSourceCatalogImplTest {

    @Test
    fun `the wizard sees every built-in aggregate, in catalogue order`() {
        val sources = BuiltInSourceCatalogImpl().sources()

        assertThat(sources.map { it.id }).containsExactlyElementsIn(BuiltInSources.CATALOG.map { it.id })
            .inOrder()
        assertThat(sources.map { it.label }).isEqualTo(BuiltInSources.CATALOG.map { it.label })
        assertThat(sources.map { it.kind }).isEqualTo(BuiltInSources.CATALOG.map { it.kind })
    }

    @Test
    fun `the catalogue is not empty and every entry is named`() {
        val sources = BuiltInSourceCatalogImpl().sources()

        assertThat(sources).isNotEmpty()
        assertThat(sources.map { it.label }).doesNotContain("")
        assertThat(sources.map { it.id }.toSet()).hasSize(sources.size)
    }
}
