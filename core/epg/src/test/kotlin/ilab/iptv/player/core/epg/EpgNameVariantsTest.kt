package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P3-5's normalization rules, and — just as important — the markers they must **not** touch: a
 * normalizer that merges `CCTV5` with `CCTV5+` would bind a channel to its neighbour's guide, which
 * §6.3 calls out as worse than a miss.
 */
class EpgNameVariantsTest {

    @Test
    fun `a feed marker is stripped from the end, repeatedly`() {
        assertThat(EpgNameVariants.canonical("cctv1高清")).isEqualTo("cctv1")
        assertThat(EpgNameVariants.canonical("cctv1标清")).isEqualTo("cctv1")
        assertThat(EpgNameVariants.canonical("湖南卫视hd")).isEqualTo("湖南卫视")
        assertThat(EpgNameVariants.canonical("湖南卫视fhd")).isEqualTo("湖南卫视")
        assertThat(EpgNameVariants.canonical("cctv1高清高清")).isEqualTo("cctv1")
    }

    @Test
    fun `punctuation and the plus sign fold mechanically`() {
        assertThat(EpgNameVariants.canonical("cctv-1综合")).isEqualTo("cctv1综合")
        assertThat(EpgNameVariants.canonical("cctv1综合")).isEqualTo("cctv1综合")
        assertThat(EpgNameVariants.canonical("cctv-13新闻")).isEqualTo("cctv13新闻")
        assertThat(EpgNameVariants.canonical("凤凰卫视-中文台")).isEqualTo("凤凰卫视中文台")
        // `+` is folded to `plus`, not deleted: guides spell CCTV-5+ three different ways.
        assertThat(EpgNameVariants.canonical("cctv5+")).isEqualTo("cctv5plus")
        assertThat(EpgNameVariants.canonical("cctv-5+体育赛事")).isEqualTo("cctv5plus体育赛事")
    }

    @Test
    fun `a quality marker that identifies a different channel is never stripped`() {
        // CCTV4K / CCTV8K are their own channels, and CCTV5+ is not CCTV5. If canonical() merged any
        // of these, a fixture channel would get its neighbour's programme list.
        assertThat(EpgNameVariants.canonical("cctv4k")).isEqualTo("cctv4k")
        assertThat(EpgNameVariants.canonical("cctv8k")).isEqualTo("cctv8k")
        assertThat(EpgNameVariants.canonical("cctv5+")).isNotEqualTo(EpgNameVariants.canonical("cctv5"))
        assertThat(EpgNameVariants.canonical("cctv4")).isNotEqualTo(EpgNameVariants.canonical("cctv4k"))
    }

    @Test
    fun `a channel whose whole name is a marker survives`() {
        // "HD" alone must not become the empty key, which would match the first guide entry it met.
        assertThat(EpgNameVariants.canonical("hd")).isEqualTo("hd")
        assertThat(EpgNameVariants.canonical("高清")).isEqualTo("高清")
        assertThat(EpgNameVariants.variants("")).isEmpty()
    }

    @Test
    fun `variants keep the key itself first and drop duplicates`() {
        val variants = EpgNameVariants.variants("cctv1")
        assertThat(variants.first()).isEqualTo("cctv1")
        assertThat(variants).containsNoDuplicates()
        // A name with nothing to fold has exactly one form: the fold must not invent matches.
        assertThat(variants).containsExactly("cctv1")
    }

    @Test
    fun `the index expands both sides and the first guide name wins`() {
        val index = EpgNameVariants.index(
            linkedMapOf(
                "cctv1" to "id.plain",
                "cctv1高清" to "id.hd",
                "cctv5+体育赛事" to "id.plus",
            ),
        )
        assertThat(index["cctv1"]!!.epgChannelId).isEqualTo("id.plain")
        assertThat(index["cctv1高清"]!!.epgChannelId).isEqualTo("id.hd")
        // The guide side is folded too, so a playlist spelling without the marker finds the HD entry.
        assertThat(index["cctv5plus"]).isNull()
        assertThat(index["cctv5+体育赛事"]!!).isEqualTo(
            EpgNameVariants.VariantHit("id.plus", "cctv5+体育赛事"),
        )
        // The direction that matters in production: the guide writes `CCTV-5+ 体育赛事`, a playlist
        // writes `CCTV5+ 体育赛事` — the guide's un-hyphenated form is registered, so the playlist key
        // finds it without any per-channel entry.
        val hyphenated = EpgNameVariants.index(linkedMapOf("cctv-5+体育赛事" to "id.hyphen"))
        assertThat(hyphenated["cctv5+体育赛事"]!!.epgChannelId).isEqualTo("id.hyphen")
    }
}
