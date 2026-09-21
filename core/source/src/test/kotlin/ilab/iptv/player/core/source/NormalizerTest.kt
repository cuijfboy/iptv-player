package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.normalize.Keys
import ilab.iptv.player.core.source.normalize.NameNormalizer
import ilab.iptv.player.core.source.normalize.UrlNormalizer
import org.junit.Test

class NameNormalizerTest {

    @Test
    fun `full width ascii and ideographic space fold to half width`() {
        assertThat(NameNormalizer.display("ＣＣＴＶ－１　综合")).isEqualTo("CCTV-1 综合")
    }

    @Test
    fun `display collapses whitespace runs and trims`() {
        assertThat(NameNormalizer.display("  CCTV   1\t综合 \n")).isEqualTo("CCTV 1 综合")
    }

    @Test
    fun `key drops all whitespace and case`() {
        assertThat(NameNormalizer.key("ＣＣＴＶ－１ 综合")).isEqualTo("cctv-1综合")
        assertThat(NameNormalizer.key("cctv-1综合")).isEqualTo("cctv-1综合")
        assertThat(NameNormalizer.key("CCTV 1 \u3000 综合")).isEqualTo("cctv1综合")
    }

    @Test
    fun `null and blank are empty keys`() {
        assertThat(NameNormalizer.key(null)).isEmpty()
        assertThat(NameNormalizer.key("   ")).isEmpty()
    }
}

class UrlNormalizerTest {

    @Test
    fun `scheme and host are lower cased, path case is kept`() {
        assertThat(UrlNormalizer.normalize("HTTP://Host.Example/Path/Case.M3U8"))
            .isEqualTo("http://host.example/Path/Case.M3U8")
    }

    @Test
    fun `default ports are dropped, others kept`() {
        assertThat(UrlNormalizer.normalize("http://host:80/a")).isEqualTo("http://host/a")
        assertThat(UrlNormalizer.normalize("https://host:443/a")).isEqualTo("https://host/a")
        assertThat(UrlNormalizer.normalize("http://host:9901/a")).isEqualTo("http://host:9901/a")
    }

    @Test
    fun `fragment is dropped, query including its token is kept`() {
        assertThat(UrlNormalizer.normalize("http://host/a?key=txiptv&authid=0#frag"))
            .isEqualTo("http://host/a?key=txiptv&authid=0")
    }

    @Test
    fun `userinfo and lookalike urls survive untouched`() {
        assertThat(UrlNormalizer.normalize("rtsp://user:pass@Host:554/live"))
            .isEqualTo("rtsp://user:pass@host/live")
        assertThat(UrlNormalizer.normalize("not a url")).isEqualTo("not a url")
        assertThat(UrlNormalizer.normalize(null)).isEmpty()
    }
}

class KeysTest {

    @Test
    fun `channel key uses the normalized name and the group title`() {
        val key = Keys.channelKey("ＣＣＴＶ－１", "央视")

        assertThat(key.nameKey).isEqualTo("cctv-1")
        assertThat(key.groupKey).isEqualTo("央视")
    }

    @Test
    fun `a missing group keys as other, because the column is not null`() {
        assertThat(Keys.groupKey(null)).isEqualTo("other")
        assertThat(Keys.groupKey("  ")).isEqualTo("other")
    }

    @Test
    fun `url hash ignores the parts normalization removes but not the token`() {
        val a = Keys.urlHash("HTTP://Host:80/a?key=txiptv")
        val b = Keys.urlHash("http://host/a?key=txiptv")
        val other = Keys.urlHash("http://host/a?key=other")

        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(64)
        assertThat(a).isNotEqualTo(other)
    }

    @Test
    fun `stream key pairs the channel id with the hash`() {
        val streamKey = Keys.streamKey(7, "http://host/a")

        assertThat(streamKey.channelId).isEqualTo(7)
        assertThat(streamKey.urlHash).isEqualTo(Keys.urlHash("http://host/a"))
    }
}
