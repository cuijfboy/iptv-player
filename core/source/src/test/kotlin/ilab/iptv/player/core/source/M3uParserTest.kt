package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.parser.M3uParser
import ilab.iptv.player.core.source.parser.PlaylistFormat
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text, sourceId = "test-source")

    @Test
    fun `attributes may be quoted, bare, out of order and missing`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="央视" tvg-chno=1 tvg-id='CCTV1' tvg-logo="http://l.example/1.png",CCTV-1
            http://a.example/cctv1/index.m3u8
            #EXTINF:-1,CCTV-2
            http://a.example/cctv2/index.m3u8
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.format).isEqualTo(PlaylistFormat.M3U)
        assertThat(outcome.entries).hasSize(2)
        assertThat(outcome.skipped).isEqualTo(0)
        val first = outcome.entries[0]
        assertThat(first.name).isEqualTo("CCTV-1")
        assertThat(first.tvgId).isEqualTo("CCTV1")
        assertThat(first.channelNo).isEqualTo(1)
        assertThat(first.logo).isEqualTo("http://l.example/1.png")
        assertThat(first.groupTitle).isEqualTo("央视")
        val second = outcome.entries[1]
        assertThat(second.name).isEqualTo("CCTV-2")
        assertThat(second.tvgId).isNull()
        assertThat(second.channelNo).isNull()
        assertThat(second.groupTitle).isNull()
    }

    @Test
    fun `a comma inside a quoted attribute does not split the title`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="新闻,财经",Trade, Channel
            http://a.example/trade/index.m3u8
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.entries).hasSize(1)
        assertThat(outcome.entries[0].groupTitle).isEqualTo("新闻,财经")
        // Everything after the FIRST unquoted comma is the title, so a comma in the title is kept.
        assertThat(outcome.entries[0].name).isEqualTo("Trade, Channel")
    }

    @Test
    fun `extgrp sets the group and extinf group-title wins over it`() {
        val text = """
            #EXTM3U
            #EXTGRP:港澳台
            #EXTINF:-1,Phoenix
            http://a.example/phoenix/index.m3u8
            #EXTGRP:港澳台
            #EXTINF:-1 group-title="卫视",Dragon TV
            http://a.example/dragon/index.m3u8
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.entries.map { it.groupTitle }).containsExactly("港澳台", "卫视").inOrder()
    }

    @Test
    fun `extvlcopt captures the user agent and referrer and tolerates other keys`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Guarded
            #EXTVLCOPT:http-user-agent=FixtureUA/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            #EXTVLCOPT:network-caching=1000
            http://a.example/guarded/index.m3u8
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.entries).hasSize(1)
        assertThat(outcome.entries[0].userAgent).isEqualTo("FixtureUA/1.0")
        assertThat(outcome.entries[0].referrer).isEqualTo("http://ref.example/")
    }

    @Test
    fun `the title falls back to tvg-name and a nameless row is skipped`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Only TVG Name",
            http://a.example/one/index.m3u8
            #EXTINF:-1 tvg-id="nameless",
            http://a.example/two/index.m3u8
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.entries).hasSize(1)
        assertThat(outcome.entries[0].name).isEqualTo("Only TVG Name")
        assertThat(outcome.skipped).isEqualTo(1)
    }

    @Test
    fun `rows without a partner are counted, not thrown`() {
        val text = """
            #EXTM3U
            http://a.example/orphan/index.m3u8
            #EXTINF:-1,No Url
            #EXTINF:-1,Left Over
        """.trimIndent()

        val outcome = parse(text)

        // The orphan URL has no #EXTINF, "No Url" is replaced by the next #EXTINF before it gets a
        // URL, and "Left Over" trails the file.
        assertThat(outcome.entries).isEmpty()
        assertThat(outcome.skipped).isEqualTo(3)
    }

    @Test
    fun `an unusable url is skipped and a trailing pipe hint is tolerated`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Bad
            not-a-url
            #EXTINF:-1,Piped
            http://a.example/piped/index.m3u8|User-Agent=Dummy
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.entries).hasSize(1)
        assertThat(outcome.entries[0].url).isEqualTo("http://a.example/piped/index.m3u8")
        assertThat(outcome.skipped).isEqualTo(1)
    }

    @Test
    fun `unknown directives and a header with attributes are ignored`() {
        val text = """
            #EXTM3U url-tvg="http://epg.example/guide.xml"
            #EXT-X-VERSION:3
            #EXTVLCOPT:http-user-agent=OrphanOpt
            #EXTINF:-1,Kept
            http://a.example/kept/index.m3u8
        """.trimIndent()

        val outcome = parse(text)

        assertThat(outcome.entries).hasSize(1)
        // #EXTVLCOPT before any #EXTINF has nothing to attach to and must not create an entry.
        assertThat(outcome.entries[0].userAgent).isNull()
        assertThat(outcome.skipped).isEqualTo(0)
    }

    @Test
    fun `crlf, blank lines and comments do not disturb the pairing`() {
        val text = "#EXTM3U\r\n\r\n# a comment\r\n#EXTINF:-1,One\r\nhttp://a.example/one/index.m3u8\r\n"

        val outcome = parse(text)

        assertThat(outcome.entries).hasSize(1)
        assertThat(outcome.entries[0].name).isEqualTo("One")
        assertThat(outcome.skipped).isEqualTo(0)
    }
}
