package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * The binding pick of EPG-BIND, as a pure function: depth first, then the arbitration the old
 * "last source overwrites" loop produced by accident, then a total order so a re-run cannot flip.
 */
class EpgBindingPreferenceTest {

    private fun candidate(
        epgChannelId: String,
        type: EpgMatchType = EpgMatchType.NAME_EXACT,
        sourceOrder: Int = 0,
        channelId: Long = 7,
    ) = EpgBindingCandidate(
        channelId = channelId,
        epgChannelId = epgChannelId,
        type = type,
        matchedOn = "matched-on-$epgChannelId",
        sourceOrder = sourceOrder,
    )

    private fun depth(vararg pairs: Pair<String, Int>): (String) -> Int {
        val counts = pairs.toMap()
        return { id -> counts[id] ?: 0 }
    }

    @Test
    fun `the guide id with the most programmes in the window wins`() {
        val shallow = candidate("mainland.cn", sourceOrder = 1)
        val deep = candidate("hk-hk", sourceOrder = 0)

        // The deeper id is proposed *first* here: depth has to beat the old "later source wins" rule,
        // not merely agree with it — that is the whole point of the card (三沙卫视/深圳卫视 were
        // moved to the deeper Hong Kong id by luck, not because anything compared the two).
        val chosen = EpgBindingPreference.choose(listOf(deep, shallow), depth("mainland.cn" to 6, "hk-hk" to 60))

        assertThat(chosen?.epgChannelId).isEqualTo("hk-hk")
    }

    @Test
    fun `the same answer comes out whichever order the sources are proposed in`() {
        val shallow = candidate("mainland.cn", sourceOrder = 0)
        val deep = candidate("hk-hk", sourceOrder = 1)
        val counts = depth("mainland.cn" to 0, "hk-hk" to 7)

        assertThat(EpgBindingPreference.choose(listOf(shallow, deep), counts)?.epgChannelId).isEqualTo("hk-hk")
        assertThat(EpgBindingPreference.choose(listOf(deep, shallow), counts)?.epgChannelId).isEqualTo("hk-hk")
    }

    @Test
    fun `a tie on depth keeps the later source, the arbitration the overwrite loop produced`() {
        val first = candidate("first.cn", sourceOrder = 0)
        val last = candidate("last.cn", sourceOrder = 3)

        val chosen = EpgBindingPreference.choose(listOf(first, last), depth("first.cn" to 4, "last.cn" to 4))

        assertThat(chosen?.epgChannelId).isEqualTo("last.cn")
    }

    @Test
    fun `a tie on EVERYTHING falls to the higher tier, then to the guide id, so the pick is total`() {
        // Two proposals from the same source with the same depth is not something the pipeline can
        // produce (one match pass emits one hit per channel), but the rule must still be a function:
        // a total order means the winner never depends on list order, so a re-run cannot flip a binding.
        val fuzzy = candidate("fuzzy.cn", type = EpgMatchType.NAME_FUZZY)
        val exact = candidate("exact.cn", type = EpgMatchType.NAME_EXACT)
        val counts = depth("fuzzy.cn" to 2, "exact.cn" to 2)

        assertThat(EpgBindingPreference.choose(listOf(fuzzy, exact), counts)?.epgChannelId).isEqualTo("exact.cn")
        assertThat(EpgBindingPreference.choose(listOf(exact, fuzzy), counts)?.epgChannelId).isEqualTo("exact.cn")

        val a = candidate("a.cn", type = EpgMatchType.TVG_ID)
        val b = candidate("b.cn", type = EpgMatchType.TVG_ID)
        val same = depth("a.cn" to 1, "b.cn" to 1)
        assertThat(EpgBindingPreference.choose(listOf(b, a), same)?.epgChannelId).isEqualTo("a.cn")
        assertThat(EpgBindingPreference.choose(listOf(a, b), same)?.epgChannelId).isEqualTo("a.cn")
    }

    @Test
    fun `a candidate with no programmes still wins when it is the only one`() {
        // 三沙卫视's mainland id declared a <channel> and published nothing. Keeping the binding is
        // right — there is no better option — and the coverage report is what calls it empty.
        val only = candidate("sansha.cn")

        val chosen = EpgBindingPreference.choose(listOf(only), depth())

        assertThat(chosen).isSameInstanceAs(only)
    }

    @Test
    fun `an empty binding loses to a thinner one that actually has something`() {
        val empty = candidate("sansha.cn", sourceOrder = 3)
        val one = candidate("sansha.hk", sourceOrder = 0)

        val chosen = EpgBindingPreference.choose(listOf(empty, one), depth("sansha.hk" to 1))

        assertThat(chosen?.epgChannelId).isEqualTo("sansha.hk")
    }

    @Test
    fun `nothing to choose from is null, not a crash`() {
        assertThat(EpgBindingPreference.choose(emptyList(), depth())).isNull()
    }
}
