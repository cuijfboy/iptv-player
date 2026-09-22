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
    fun `script folding runs before the feed-marker rule`() {
        // Rule order is the contract: 標清 is a feed marker only *after* it has been folded to 标清, so
        // a Traditional guide name drops it like any other. If the order were reversed, `cctv1標清`
        // would keep its marker and miss the plain guide entry.
        // Inputs are the keys the matcher actually passes (`EpgNameKey` output: no spaces, lower case).
        assertThat(EpgNameVariants.canonical("cctv1標清")).isEqualTo("cctv1")
        assertThat(EpgNameVariants.canonical("cctv-1標清")).isEqualTo("cctv1")
        assertThat(EpgNameVariants.canonical("tvb星河頻道")).isEqualTo("tvb星河频道")
        // The other rules still compose on top of it: script + punctuation + `+`.
        assertThat(EpgNameVariants.canonical("中國中央電視台-5＋體育")).isEqualTo("中国中央电视台5plus体育")
    }

    @Test
    fun `the script fold is applied to the guide side too and the raw form stays first`() {
        val index = EpgNameVariants.index(linkedMapOf("澳視澳門" to listOf("mo.id")))
        // A Simplified playlist key finds the Traditional guide name...
        val hit = index["澳视澳门"]!!.single()
        assertThat(hit.epgChannelId).isEqualTo("mo.id")
        // ...and the reported guide key is the guide's own spelling, never the folded one: folding is a
        // lookup key, the display name the user sees is untouched.
        assertThat(hit.guideKey).isEqualTo("澳視澳門")
        assertThat(index["澳視澳門"]!!.single().epgChannelId).isEqualTo("mo.id")
        // The least-mutated form is still the first variant, so a hit explains the smallest change.
        assertThat(EpgNameVariants.variants("澳視澳門").first()).isEqualTo("澳視澳門")
        assertThat(EpgNameVariants.variants("澳視澳門")).contains("澳视澳门")
    }

    @Test
    fun `a name with nothing to fold keeps exactly the variants it had before the script rule`() {
        // The script fold must not inflate the variant set for the mainland names that dominate a
        // playlist: 16 masks collapse back to one form when there is no Traditional character.
        assertThat(EpgNameVariants.variants("cctv1")).containsExactly("cctv1")
        assertThat(EpgNameVariants.variants("cctv5+")).containsExactly("cctv5+", "cctv5plus")
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
    fun `the numbered handle folds a guide column name onto the playlist's bare number`() {
        // The EPG-BIND root cause: the mainland guide spells the channel by its column (`CCTV-2 财经`)
        // and the playlist by the number (`CCTV2`). Neither the name key nor punctuation can bridge
        // them, so both must reduce to the same handle — otherwise the playlist's only match is the
        // guide's zero-programme `CCTV2` stub.
        assertThat(EpgNameVariants.variants("cctv-2财经")).contains("cctv2")
        assertThat(EpgNameVariants.variants("cctv2")).containsExactly("cctv2")
        assertThat(EpgNameVariants.variants("cctv-16奥林匹克")).contains("cctv16")
        assertThat(EpgNameVariants.variants("cctv-5+体育赛事")).contains("cctv5+")
        // The handle is a lookup form, not a canonical rewrite: `canonical` stays the four P3-5 rules
        // (the ones a hit is explained with), so nothing that pinned those numbers moves.
        assertThat(EpgNameVariants.canonical("cctv-2财经")).isEqualTo("cctv2财经")
    }

    @Test
    fun `the numbered handle never folds a different channel onto the plain number`() {
        // The same "错配比不匹配更糟" boundary as the feed-marker rule: a Latin tail is a different
        // channel (`CCTV4K` is not `CCTV4`), and a bracketed region marker is a different feed.
        assertThat(EpgNameVariants.variants("cctv4k")).containsExactly("cctv4k")
        assertThat(EpgNameVariants.variants("cctv-8k")).containsExactly("cctv-8k", "cctv8k")
        assertThat(EpgNameVariants.variants("cctv-4(亚洲)")).doesNotContain("cctv4")
        assertThat(EpgNameVariants.variants("cctv5+")).doesNotContain("cctv5")
    }

    @Test
    fun `the index expands both sides and keeps every id a variant stands for`() {
        val index = EpgNameVariants.index(
            linkedMapOf(
                "cctv1" to listOf("id.plain"),
                "cctv1高清" to listOf("id.hd"),
                "cctv5+体育赛事" to listOf("id.plus"),
            ),
        )
        // Two guide names reduce to `cctv1`, so both ids are candidates, in guide order. Before
        // EPG-BIND the first one silently shadowed the second.
        assertThat(index["cctv1"]!!.map { it.epgChannelId })
            .containsExactly("id.plain", "id.hd").inOrder()
        assertThat(index["cctv1高清"]!!.map { it.epgChannelId }).containsExactly("id.hd")
        // The guide side is folded too, so a playlist spelling without the marker finds the HD entry.
        assertThat(index["cctv5plus"]).isNull()
        assertThat(index["cctv5+体育赛事"]!!.single()).isEqualTo(
            EpgNameVariants.VariantHit("id.plus", "cctv5+体育赛事"),
        )
        // The direction that matters in production: the guide writes `CCTV-5+ 体育赛事`, a playlist
        // writes `CCTV5+ 体育赛事` — the guide's un-hyphenated form is registered, so the playlist key
        // finds it without any per-channel entry.
        val hyphenated = EpgNameVariants.index(linkedMapOf("cctv-5+体育赛事" to listOf("id.hyphen")))
        assertThat(hyphenated["cctv5+体育赛事"]!!.single().epgChannelId).isEqualTo("id.hyphen")
    }
}
