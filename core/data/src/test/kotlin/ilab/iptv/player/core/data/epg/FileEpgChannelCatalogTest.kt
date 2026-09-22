package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.model.EpgChannelRef
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-4's guide-channel cache: one EPG run publishes the guide's `<channel>` list, the picker reads it
 * back. The file format must tolerate a malformed line (docs/02 §11: never a crash, and a bad line is
 * skipped rather than failing the whole read).
 */
@RunWith(AndroidJUnit4::class)
class FileEpgChannelCatalogTest {

    private lateinit var store: FileEpgChannelCatalog
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = RoomFixtures.context()
        file = File(context.filesDir, "epg-channels.tsv")
        file.delete()
        store = FileEpgChannelCatalog(context, TestDispatcherProvider())
    }

    @Test
    fun `a run's channel list round trips, sorted by display name`() = runBlocking<Unit> {
        store.replaceAll(
            listOf(
                EpgChannelRef("tw.cti", "中天新闻"),
                EpgChannelRef("hk.tvb", "TVB星河频道"),
                EpgChannelRef("cn.cctv1", "CCTV-1 综合"),
            ),
        )

        val read = store.observe().first()
        assertThat(read.map { it.id }).containsExactly("cn.cctv1", "hk.tvb", "tw.cti").inOrder()
        assertThat(read.single { it.id == "cn.cctv1" }.displayName).isEqualTo("CCTV-1 综合")
    }

    @Test
    fun `a blank catalogue is empty rather than an error`() = runBlocking<Unit> {
        assertThat(store.observe().first()).isEmpty()
    }

    @Test
    fun `a malformed line is skipped, not fatal`() = runBlocking<Unit> {
        file.writeText("cn.cctv1\tCCTV-1\nno-tab-here\n\tmissing-id\nhk.tvb\tTVB星河频道\n")

        val read = FileEpgChannelCatalog(RoomFixtures.context(), TestDispatcherProvider()).observe().first()
        assertThat(read.map { it.id }).containsExactly("cn.cctv1", "hk.tvb")
    }
}
