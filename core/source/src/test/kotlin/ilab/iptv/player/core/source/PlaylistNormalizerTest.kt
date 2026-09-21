package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.source.normalize.PlaylistNormalizer
import org.junit.Test

class PlaylistNormalizerTest {

    private fun entry(name: String, url: String, group: String? = null, source: String = "s1") =
        RawEntry(name = name, url = url, groupTitle = group, sourceId = source)

    @Test
    fun `the same name in two groups stays two channels`() {
        val entries = listOf(
            entry("CCTV-1", "http://a.example/1.m3u8", "央视"),
            entry("CCTV-1", "http://b.example/1.m3u8", "地方/其他"),
        )

        val playlist = PlaylistNormalizer.normalize(entries, interleaveBySource = false)

        assertThat(playlist.channels).hasSize(2)
        assertThat(playlist.channels.map { it.groupKey }).containsExactly("央视", "地方/其他").inOrder()
        assertThat(playlist.channelDedupe.unique).isEqualTo(2)
    }

    @Test
    fun `the same name in one group is one channel with both streams`() {
        val entries = listOf(
            entry("CCTV-1", "http://a.example/1.m3u8", "央视"),
            entry("CCTV-1", "http://b.example/1.m3u8", "央视"),
        )

        val playlist = PlaylistNormalizer.normalize(entries, interleaveBySource = false)

        assertThat(playlist.channels).hasSize(1)
        assertThat(playlist.channels[0].streams).hasSize(2)
        assertThat(playlist.channels[0].sourceIds).containsExactly("s1")
    }

    @Test
    fun `one url under two channels is allowed by the index but capped by dedupe`() {
        val entries = listOf(
            entry("CCTV-1", "http://shared.example/live.m3u8", "央视"),
            entry("CCTV-1 高清", "http://shared.example/live.m3u8", "央视"),
        )

        // Channel-level view: UNIQUE(name_key, group_key) does not look at the URL, so both survive.
        val channels = PlaylistNormalizer.groupChannels(PlaylistNormalizer.normalizeEntries(entries))
        assertThat(channels).hasSize(2)
        assertThat(channels.map { it.streams }).hasSize(2)

        // Stream-level view: the second sighting of the same url_hash is what SRC_DEDUPE removes.
        val deduped = PlaylistNormalizer.dedupeByUrlHash(entries, interleaveBySource = false)
        assertThat(deduped.entries).hasSize(1)
        assertThat(deduped.report.removed).isEqualTo(1)
    }

    @Test
    fun `a full-width name lands on the same channel key as its half-width spelling`() {
        val entries = listOf(
            entry("ＣＣＴＶ－１ 综合", "http://a.example/1.m3u8", "央视"),
            entry("cctv-1综合", "http://b.example/1.m3u8", "央视"),
        )

        val playlist = PlaylistNormalizer.normalize(entries, interleaveBySource = false)

        assertThat(playlist.channels).hasSize(1)
        assertThat(playlist.channels[0].nameKey).isEqualTo("cctv-1综合")
        assertThat(playlist.channels[0].name).isEqualTo("CCTV-1 综合")
        assertThat(playlist.streamDedupe.removed).isEqualTo(0)
    }

    @Test
    fun `interleaving sources changes which duplicate survives`() {
        val entries = listOf(
            entry("A1", "http://a.example/1.m3u8", source = "A"),
            entry("A2", "http://a.example/2.m3u8", source = "A"),
            entry("B1", "http://a.example/2.m3u8", source = "B"),
            entry("B2", "http://b.example/3.m3u8", source = "B"),
        )

        val interleaved = PlaylistNormalizer.dedupeByUrlHash(entries, interleaveBySource = true)
        val sequential = PlaylistNormalizer.dedupeByUrlHash(entries, interleaveBySource = false)

        assertThat(interleaved.report.unique).isEqualTo(3)
        assertThat(interleaved.report.perSource).containsExactly("A", 1, "B", 2)
        assertThat(sequential.report.unique).isEqualTo(3)
        assertThat(sequential.report.perSource).containsExactly("A", 2, "B", 1)
    }

    @Test
    fun `duplicate rows inside one source are removed and reported`() {
        val entries = listOf(
            entry("CCTV-1", "http://a.example/1.m3u8"),
            entry("CCTV-1", "http://a.example/1.m3u8"),
            entry("CCTV-2", "http://a.example/2.m3u8"),
        )

        val deduped = PlaylistNormalizer.dedupeByUrlHash(entries)

        assertThat(deduped.report.raw).isEqualTo(3)
        assertThat(deduped.report.unique).isEqualTo(2)
        assertThat(deduped.report.removed).isEqualTo(1)
        assertThat(deduped.report.perSource).containsExactly("s1", 2)
    }
}
