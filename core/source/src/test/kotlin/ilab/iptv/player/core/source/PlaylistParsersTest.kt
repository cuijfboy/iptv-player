package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.source.parser.PlaylistFormat
import ilab.iptv.player.core.source.parser.PlaylistParsers
import org.junit.Test

class PlaylistParsersTest {

    @Test
    fun `format detection reads the header, the first extinf, or falls back to txt`() {
        assertThat(PlaylistParsers.detectFormat("#EXTM3U\n#EXTINF:-1,A\nhttp://a.example/1.m3u8"))
            .isEqualTo(PlaylistFormat.M3U)
        assertThat(PlaylistParsers.detectFormat("\n\n#EXTINF:-1,A\nhttp://a.example/1.m3u8"))
            .isEqualTo(PlaylistFormat.M3U)
        assertThat(PlaylistParsers.detectFormat("CCTV-1,http://a.example/1.m3u8"))
            .isEqualTo(PlaylistFormat.TXT)
    }

    @Test
    fun `the byte entry point decodes, detects and parses in one call`() {
        val result = PlaylistParsers.parse(Fixtures.bytes("m3u/gb18030.m3u"), sourceId = "gb-source")

        val outcome = (result as AppResult.Ok).value
        assertThat(outcome.format).isEqualTo(PlaylistFormat.M3U)
        assertThat(outcome.entries).hasSize(3)
        assertThat(outcome.charset).isEqualTo("GB18030")
        assertThat(outcome.charsetEventCode).isEqualTo(EventCodes.NET_CHARSET_FALLBACK)
    }

    @Test
    fun `a clean utf8 playlist emits no charset fallback code`() {
        val result = PlaylistParsers.parse(Fixtures.bytes("m3u/valid.m3u"), sourceId = "utf8-source")

        val outcome = (result as AppResult.Ok).value
        assertThat(outcome.charsetEventCode).isNull()
        assertThat(outcome.eventCode).isEqualTo(EventCodes.SRC_PARSE_OK)
        assertThat(outcome.formatLabel).isEqualTo("m3u")
    }

    @Test
    fun `the format can be forced when detection would guess wrong`() {
        val outcome = PlaylistParsers.parse(
            text = "#EXTINF:-1,Name\nhttp://a.example/1.m3u8",
            sourceId = "forced",
            format = PlaylistFormat.M3U,
        )

        assertThat(outcome.entries).hasSize(1)
    }

    @Test
    fun `an unknown charset hint does not fail the parse`() {
        val result = PlaylistParsers.parse(
            Fixtures.bytes("m3u/valid.m3u"),
            sourceId = "hinted",
            charsetHint = "definitely-not-a-charset",
        )

        val outcome = (result as AppResult.Ok).value
        assertThat(outcome.entries).hasSize(4)
        assertThat(outcome.charset).isEqualTo("UTF-8")
    }

    @Test
    fun `every fixture parses without throwing`() {
        val names = listOf(
            "m3u/valid.m3u",
            "m3u/attrs-edge.m3u",
            "m3u/malformed.m3u",
            "m3u/bom-crlf.m3u",
            "m3u/gb18030.m3u",
            "m3u/header-only.m3u",
            "m3u/empty.m3u",
            "m3u/real-excerpt.m3u",
            "txt/two-column.txt",
            "txt/three-column.txt",
            "txt/genre.txt",
            "txt/malformed.txt",
            "txt/gb18030.txt",
        )

        for (name in names) {
            val result = PlaylistParsers.parse(Fixtures.bytes(name), sourceId = name)
            assertThat(result).isInstanceOf(AppResult.Ok::class.java)
        }
    }
}
