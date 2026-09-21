package ilab.iptv.player.core.data.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.epg.EpgNameKey
import ilab.iptv.player.core.source.normalize.NameNormalizer
import ilab.iptv.player.core.source.normalize.Keys
import org.junit.Test

/**
 * The EPG matcher's name key must be **byte-for-byte** the key the playlist normalizer wrote into
 * `channel.name_key`, because tier 2 of the match chain compares the two.
 *
 * They live in different modules by necessity — `:core:epg` may not declare `:core:source`
 * (docs/02 §3.2) — which is exactly the kind of split that drifts silently. `:core:data` is the one
 * module allowed to see both, so the assertion lives here rather than as two hand-copied expectations
 * that could be updated one at a time. The corpus is deliberately the awkward end: full-width digits
 * and letters, the ideographic space, an ideographic parenthesis, mixed case, and a trailing space.
 */
class NameKeyParityTest {

    private val corpus = listOf(
        "CCTV-1 综合",
        "cctv-1综合",
        "ＣＣＴＶ－１ 综合",
        "CCTV-1　综合",
        "湖南卫视-高清",
        "  CGTN  纪录  ",
        "凤凰卫视中文台",
        "CCTV5+体育赛事",
        "ＢＴＶ－北京　卫视",
        "（测试）频道",
        "",
        "   ",
    )

    @Test
    fun `the epg name key and the playlist name key agree on the whole corpus`() {
        for (name in corpus) {
            assertThat(EpgNameKey.key(name)).isEqualTo(Keys.nameKey(name))
        }
    }

    @Test
    fun `null and blank names fold to the empty key on both sides`() {
        assertThat(EpgNameKey.key(null)).isEqualTo(Keys.nameKey(null))
        assertThat(EpgNameKey.key(null)).isEmpty()
    }

    @Test
    fun `the display form is folded identically too`() {
        // `display` is not used by the matcher, but it is the shared half of the two implementations;
        // a divergence there would mean the two files are no longer copies of one algorithm.
        for (name in corpus) {
            assertThat(EpgNameKey.display(name)).isEqualTo(NameNormalizer.display(name))
        }
    }
}
