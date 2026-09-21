package ilab.iptv.player.core.source.deep

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlsManifestParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        1080p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2"
        low/index.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:2581
        #EXTINF:6.006,
        seg-2581.ts
        #EXTINF:6.006,
        seg-2582.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `a master playlist yields its variants with declared codecs and resolution`() {
        val parsed = HlsManifestParser.parse(master, "https://cdn.invalid/live/master.m3u8")!!
        assertThat(parsed.isMaster).isTrue()
        assertThat(parsed.variants).hasSize(2)
        val first = parsed.variants.first()
        assertThat(first.uri).isEqualTo("https://cdn.invalid/live/1080p/index.m3u8")
        assertThat(first.bandwidth).isEqualTo(4_000_000)
        assertThat(first.width).isEqualTo(1920)
        assertThat(first.height).isEqualTo(1080)
        assertThat(first.codecs).containsExactly("avc1.640028", "mp4a.40.2")
        assertThat(parsed.segments).isEmpty()
    }

    @Test
    fun `a media playlist yields its segments and media sequence`() {
        val parsed = HlsManifestParser.parse(media, "https://cdn.invalid/live/1080p/index.m3u8")!!
        assertThat(parsed.isMaster).isFalse()
        assertThat(parsed.mediaSequence).isEqualTo(2581)
        assertThat(parsed.segments)
            .containsExactly(
                "https://cdn.invalid/live/1080p/seg-2581.ts",
                "https://cdn.invalid/live/1080p/seg-2582.ts",
            )
            .inOrder()
    }

    @Test
    fun `absolute uris are kept as they are`() {
        val body = "#EXTM3U\n#EXTINF:6,\nhttps://other.invalid/seg.ts\n"
        val parsed = HlsManifestParser.parse(body, "https://cdn.invalid/live/index.m3u8")!!
        assertThat(parsed.segments).containsExactly("https://other.invalid/seg.ts")
    }

    @Test
    fun `a body that is not a playlist is not a manifest`() {
        assertThat(HlsManifestParser.parse("hello", "https://x.invalid/a.m3u8")).isNull()
        assertThat(HlsManifestParser.parse("", "https://x.invalid/a.m3u8")).isNull()
    }

    @Test
    fun `unknown tags do not break the parse`() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-PROGRAM-DATE-TIME:2026-09-22T00:00:00Z
            #EXTINF:6.0,
            seg-1.ts
        """.trimIndent()
        val parsed = HlsManifestParser.parse(body, "https://cdn.invalid/live/index.m3u8")!!
        assertThat(parsed.segments).containsExactly("https://cdn.invalid/live/seg-1.ts")
    }

    @Test
    fun `attribute parsing respects quotes`() {
        val attributes = HlsManifestParser.attributes(
            """BANDWIDTH=1000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"""",
        )
        assertThat(attributes["BANDWIDTH"]).isEqualTo("1000")
        assertThat(attributes["RESOLUTION"]).isEqualTo("1280x720")
        assertThat(attributes["CODECS"]).isEqualTo("avc1.4d401f,mp4a.40.2")
    }
}
