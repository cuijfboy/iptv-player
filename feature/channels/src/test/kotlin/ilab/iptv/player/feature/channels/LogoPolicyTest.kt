package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P2-3: which logo URLs are attempted, and what the cache is keyed on (docs/04 P2-3 "台标 URL 空/坏
 * 的处理、缓存键策略"). The loader itself is a thin Coil wrapper; these two decisions are the ones a
 * wrong implementation makes silently (a fetch for every blank logo; the same image stored twice).
 */
class LogoPolicyTest {

    @Test
    fun `a blank or missing url is not attempted`() {
        assertThat(LogoPolicy.normalize(null)).isNull()
        assertThat(LogoPolicy.normalize("")).isNull()
        assertThat(LogoPolicy.normalize("   ")).isNull()
    }

    @Test
    fun `a non-http url is not attempted`() {
        // A relative path, a device file and a typo'd scheme are all "there is no logo to fetch".
        assertThat(LogoPolicy.normalize("logo/cctv.png")).isNull()
        assertThat(LogoPolicy.normalize("file:///sdcard/logo.png")).isNull()
        assertThat(LogoPolicy.normalize("htp://example.com/logo.png")).isNull()
        assertThat(LogoPolicy.normalize("http://")).isNull()
    }

    @Test
    fun `a usable url is returned unchanged, scheme case-insensitive`() {
        val url = "http://p12.demo.invalid/logo/cctv.png"
        assertThat(LogoPolicy.normalize(url)).isEqualTo(url)
        assertThat(LogoPolicy.normalize("  $url  ")).isEqualTo(url)
        assertThat(LogoPolicy.normalize("HTTPS://cdn.example.com/l.png")).isEqualTo("HTTPS://cdn.example.com/l.png")
    }

    @Test
    fun `the cache key drops the fragment but keeps the query`() {
        assertThat(LogoPolicy.cacheKey("http://h/l.png")).isEqualTo("http://h/l.png")
        assertThat(LogoPolicy.cacheKey("http://h/l.png#anchor")).isEqualTo("http://h/l.png")
        assertThat(LogoPolicy.cacheKey("http://h/l.png?v=2#anchor")).isEqualTo("http://h/l.png?v=2")
        // Two fragments on the same image must share a cache entry, not fetch twice.
        assertThat(LogoPolicy.cacheKey("http://h/l.png#a")).isEqualTo(LogoPolicy.cacheKey("http://h/l.png#b"))
    }
}
