package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.source.provider.RemotePlaylistSourceProvider
import ilab.iptv.player.core.source.provider.SourceDescriptor
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RemotePlaylistSourceProviderTest {

    private val logger = RecordingLogger()
    private val clock = FakeClock()

    private fun descriptor(kind: SourceKind = SourceKind.M3U) =
        SourceDescriptor(id = "test.source", label = "Test", kind = kind, url = "http://fake.local/list")

    private fun provider(handler: (ilab.iptv.player.core.network.HttpRequest) -> AppResult<ilab.iptv.player.core.network.HttpResponse>) =
        RemotePlaylistSourceProvider(descriptor(), FakeHttpFetcher(handler), logger)

    @Test
    fun `an M3U body is fetched and parsed into raw entries`() {
        val body = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1
            http://stream.invalid/cctv1.m3u8
        """.trimIndent().toByteArray()
        val result = runBlocking { provider(FakeHttpFetcher.ok(body)).fetch(clock) }
        val entries = (result as AppResult.Ok).value
        assertThat(entries).hasSize(1)
        assertThat(entries[0].name).isEqualTo("CCTV-1")
        assertThat(entries[0].sourceId).isEqualTo("test.source")
        assertThat(logger.count(EventCodes.SRC_FETCH_OK)).isEqualTo(1)
        assertThat(logger.count(EventCodes.SRC_PARSE_OK)).isEqualTo(1)
    }

    @Test
    fun `a TXT body is detected and parsed`() {
        val body = "卫视,#genre#\n湖南卫视,http://stream.invalid/hn.m3u8\n".toByteArray()
        val result = runBlocking { provider(FakeHttpFetcher.ok(body)).fetch(clock) }
        val entries = (result as AppResult.Ok).value
        assertThat(entries).hasSize(1)
        assertThat(entries[0].groupTitle).isEqualTo("卫视")
    }

    @Test
    fun `a GB18030 body falls back and logs NET_CHARSET_FALLBACK`() {
        val body = "湖南卫视,http://stream.invalid/hn.m3u8\n".toByteArray(Charset.forName("GB18030"))
        val result = runBlocking { provider(FakeHttpFetcher.ok(body)).fetch(clock) }
        assertThat((result as AppResult.Ok).value[0].name).isEqualTo("湖南卫视")
        assertThat(logger.count(EventCodes.NET_CHARSET_FALLBACK)).isEqualTo(1)
    }

    @Test
    fun `a retrieval failure is returned and logged as SRC_FETCH_FAIL`() {
        val result = runBlocking { provider(FakeHttpFetcher.fail()).fetch(clock) }
        assertThat(result).isInstanceOf(AppResult.Err::class.java)
        assertThat(logger.count(EventCodes.SRC_FETCH_FAIL)).isEqualTo(1)
    }

    @Test
    fun `a body with no parseable rows still succeeds with an empty list`() {
        val result = runBlocking { provider(FakeHttpFetcher.ok("not a playlist\n".toByteArray())).fetch(clock) }
        assertThat((result as AppResult.Ok).value).isEmpty()
        assertThat(logger.count(EventCodes.SRC_PARSE_OK)).isEqualTo(1)
    }
}
