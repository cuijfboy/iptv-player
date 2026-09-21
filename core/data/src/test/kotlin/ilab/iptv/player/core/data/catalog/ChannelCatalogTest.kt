package ilab.iptv.player.core.data.catalog

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.Fixtures
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.source.parser.PlaylistFormat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the P1-2 pipeline on the shipped fixture: parse → normalize/dedupe → map → store.
 *
 * The numbers asserted here are the ones the device run and `docs/05-过程记录/05-P0构建验证.md` §12
 * report, so the fixture, the pipeline and the record cannot drift apart.
 */
class ChannelCatalogTest {

    private val store = ChannelStore()
    private val catalog = ChannelCatalog(store, FakeClock())
    private val fixture = Fixtures.text(Fixtures.BASELINE_PLAYLIST)

    @Test
    fun `the bundled fixture loads to 658 channels over 670 rows`() = test {
        val report = catalog.load(fixture, sourceId = "p1-2-fixture")

        assertThat(report.format).isEqualTo(PlaylistFormat.M3U)
        assertThat(report.rawEntries).isEqualTo(670)
        assertThat(report.skipped).isEqualTo(0)
        assertThat(report.channels).isEqualTo(658)
        assertThat(report.streams).isEqualTo(670)
        // 12 rows repeat a (name, group) pair already in the list: they must merge into the existing
        // channel as extra streams, which is what the docs/02 §5.1 unique index allows. No URL is
        // repeated, so the stream-level dedupe removes nothing.
        assertThat(report.streamDedupe.removed).isEqualTo(0)
        assertThat(report.channelMerge.raw).isEqualTo(670)
        assertThat(report.channelMerge.unique).isEqualTo(658)
    }

    @Test
    fun `the group mix matches the real baseline shape`() = test {
        val report = catalog.load(fixture, sourceId = "p1-2-fixture")

        assertThat(report.groups[ChannelGroup.CCTV]).isEqualTo(80)
        assertThat(report.groups[ChannelGroup.SATELLITE]).isEqualTo(69)
        assertThat(report.groups[ChannelGroup.HK_MO_TW]).isEqualTo(7)
        assertThat(report.groups[ChannelGroup.LOCAL]).isEqualTo(500)
        assertThat(report.groups[ChannelGroup.OTHER]).isEqualTo(2)
    }

    @Test
    fun `the store receives consistent channel and stream ids`() = test {
        catalog.load(fixture, sourceId = "p1-2-fixture")

        val channels = store.channels.value
        val streams = store.streams.value
        assertThat(channels).hasSize(658)
        assertThat(streams).hasSize(670)
        assertThat(channels.map { it.id }).containsExactlyElementsIn((1L..658L).toList()).inOrder()
        assertThat(streams.map { it.channelId }.toSet())
            .containsExactlyElementsIn(channels.map { it.id }.toSet())
        assertThat(channels.sumOf { it.streamCount }).isEqualTo(streams.size)
    }

    @Test
    fun `the D12 middle tier and the logo path survive the mapping`() = test {
        catalog.load(fixture, sourceId = "p1-2-fixture")

        val channels = store.channels.value
        // 15 fixture rows carry tvg-chno; every one must reach the model as channelNo.
        assertThat(channels.count { it.channelNo != null }).isEqualTo(15)
        // 20 rows carry tvg-logo; the channel keeps the first one it saw.
        assertThat(channels.count { it.logoUrl != null }).isEqualTo(20)
        assertThat(channels.first { it.name == "CCTV1" }.channelNo).isEqualTo(1)
    }

    @Test
    fun `key derivation and classification come from the domain policy`() = test {
        catalog.load(fixture, sourceId = "p1-2-fixture")

        val cctv1 = store.channels.value.first { it.name == "CCTV1" }
        assertThat(cctv1.nameKey).isEqualTo("cctv1")
        assertThat(cctv1.groupKey).isEqualTo("央视")
        assertThat(cctv1.groupTitle).isEqualTo("央视")
        assertThat(cctv1.group).isEqualTo(ChannelGroup.CCTV)
        assertThat(cctv1.streamCount).isEqualTo(2)
        // Nothing in P1-2 can know codecs, resolution or score: they stay at their empty values
        // instead of being guessed (deep validation is P2-3).
        assertThat(store.streams.value.first { it.channelId == cctv1.id }.quality).isNull()
    }

    @Test
    fun `a second load replaces the catalog instead of appending to it`() = test {
        catalog.load(fixture, sourceId = "p1-2-fixture")
        catalog.load(fixture, sourceId = "p1-2-fixture")

        assertThat(store.channels.value).hasSize(658)
        assertThat(store.streams.value).hasSize(670)
    }

    @Test
    fun `an empty playlist loads to an empty catalog`() = test {
        val report = catalog.load("#EXTM3U\n", sourceId = "empty")

        assertThat(report.rawEntries).isEqualTo(0)
        assertThat(report.channels).isEqualTo(0)
        assertThat(store.channels.value).isEmpty()
    }

    /** `load` now writes through the (suspending) `CatalogSink`, so the tests run in a coroutine. */
    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
}
