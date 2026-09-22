package ilab.iptv.player.core.data.catalog

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.Fixtures
import ilab.iptv.player.core.data.refresh.FakeClock
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.source.parser.PlaylistFormat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files

/**
 * SNAPSHOT-1: the file the app seeds a fresh install with.
 *
 * This is a *data* test, and it is deliberately strict about two different things:
 * - **shape** — the snapshot has to be a usable TV playlist (enough channels, the four groups a
 *   viewer expects, `tvg-id` / `tvg-name` / `group-title` on every row, one stream per channel so the
 *   list the user sees is exactly the list that was measured);
 * - **hygiene** — the file ships in a *public* repository, so it may not carry a private LAN address
 *   (the same rule `tools/ci/sensitive-info-guard.sh` enforces repo-wide) and may not be the
 *   `.invalid`-hosted synthetic fixture by accident.
 *
 * It reads the asset from the source tree rather than through an `AssetManager` because the file
 * lives in `:app`'s assets while this test lives in `:core:data` — and because a plain read is what
 * keeps the assertion about *content* independent of the Robolectric asset plumbing.
 */
class SnapshotPlaylistTest {

    private val store = ChannelStore()
    private val catalog = ChannelCatalog(store, FakeClock())

    private val assetPath = "app/src/main/assets/${AssetBundledPlaylist.SNAPSHOT_ASSET}"
    private val provenancePath = "app/src/main/assets/snapshot/PROVENANCE.md"
    private val text: String by lazy { Fixtures.text(assetPath) }
    private val rows: List<String> by lazy { text.lines().filter { it.startsWith("#EXTINF") } }

    @Test
    fun `the snapshot and its provenance ship where the production graph reads them`() {
        // Guards the constant and the physical path against drifting apart: DataModule opens
        // `SNAPSHOT_ASSET` out of the APK's merged assets, so the file must sit at that path.
        assertThat(AssetBundledPlaylist.SNAPSHOT_ASSET).isEqualTo("snapshot/channels.m3u")
        assertThat(Files.isRegularFile(Fixtures.root.resolve(assetPath))).isTrue()
        assertThat(Files.isRegularFile(Fixtures.root.resolve(provenancePath))).isTrue()
        // The production binding must not still be pointing at the synthetic fixture.
        assertThat(AssetBundledPlaylist.SNAPSHOT_ASSET)
            .isNotEqualTo(AssetBundledPlaylist.DEFAULT_ASSET)
    }

    @Test
    fun `the snapshot loads into a channel list a fresh install can use`() = test {
        val report = catalog.load(text, AssetBundledPlaylist.SNAPSHOT_SOURCE_ID)

        assertThat(report.format).isEqualTo(PlaylistFormat.M3U)
        assertThat(report.channels).isAtLeast(150)
        // One stream per channel: nothing was merged, so what was measured is what is shown.
        assertThat(report.streams).isEqualTo(report.channels)
        assertThat(store.channels.value).hasSize(report.channels)
    }

    @Test
    fun `the snapshot covers the groups a viewer looks for`() = test {
        catalog.load(text, AssetBundledPlaylist.SNAPSHOT_SOURCE_ID)
        val groups = store.channels.value.map { it.group }.toSet()

        assertThat(groups).containsAtLeast(
            ChannelGroup.CCTV,
            ChannelGroup.SATELLITE,
            ChannelGroup.LOCAL,
        )
        // "体育" (sports) is a keyword the grouping table does not classify, so it lands in OTHER or
        // LOCAL by its title; assert it is *present as content* instead of inventing a group for it.
        assertThat(store.channels.value.count { it.name.contains("体育") || it.name.contains("足球") })
            .isAtLeast(1)
    }

    @Test
    fun `every row carries the metadata the TV list reads`() {
        assertThat(rows).hasSize(text.lines().count { it.startsWith("http") })
        assertThat(rows.count { !it.contains("tvg-id=\"") }).isEqualTo(0)
        assertThat(rows.count { !it.contains("tvg-name=\"") }).isEqualTo(0)
        assertThat(rows.count { !it.contains("group-title=\"") }).isEqualTo(0)
        // One number per row, so the browse list shows 1..N instead of a wall of "auto".
        assertThat(rows.count { !Regex("tvg-chno=\\d+").containsMatchIn(it) }).isEqualTo(0)
        // The display name (after the comma) is what the row is; blank names would show as "".
        assertThat(rows.count { it.substringAfterLast(",").isBlank() }).isEqualTo(0)
    }

    @Test
    fun `the shipped snapshot carries no LAN address and is not the synthetic fixture`() {
        val privateIp = Regex("(^|[^0-9A-Za-z.])((192\\.168|10)\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})")
        assertThat(privateIp.containsMatchIn(text)).isFalse()
        // Credential-looking query keys the repo guard would reject.
        assertThat(Regex("(^|[^0-9A-Za-z_])(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|access[_-]?token|auth[_-]?token)[ \\t]*[:=]")
            .containsMatchIn(text)).isFalse()
        assertThat(text).doesNotContain(".invalid")
    }

    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
}
