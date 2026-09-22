package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Traditional→Simplified table, and the properties that make it safe to apply to *both* sides of a
 * match: it is index-aligned, it never deletes a character, it is idempotent, and it merges only the
 * characters that are the same character in two scripts.
 */
class EpgTraditionalFoldTest {

    @Test
    fun `the two halves are index-aligned and every entry is a real substitution`() {
        val traditional = EpgTraditionalFold.TRADITIONAL_CHARS
        val simplified = EpgTraditionalFold.SIMPLIFIED_CHARS
        assertThat(traditional.length).isEqualTo(simplified.length)
        // The table size is a decision, not a drift: 126 characters derived from the four guides +
        // the shipped fixture, plus the four margin characters named in the KDoc.
        assertThat(EpgTraditionalFold.size).isEqualTo(130)
        assertThat(traditional.toSet()).hasSize(traditional.length)
        for (index in traditional.indices) {
            // A Traditional key that folds to itself would be a table entry doing nothing.
            assertThat(simplified[index]).isNotEqualTo(traditional[index])
        }
    }

    @Test
    fun `only the documented character pair folds onto the same simplified character`() {
        // Two Traditional characters sharing one Simplified form are the one shape that can merge two
        // guide names. `綫`/`線` are the same character written two ways (`無綫新聞台` == `無線新聞台`),
        // so the pair is intended; anything else appearing here is a table mistake, and this test is
        // the place it surfaces.
        val collisions = EpgTraditionalFold.TRADITIONAL_CHARS.indices
            .groupBy { EpgTraditionalFold.SIMPLIFIED_CHARS[it] }
            .filterValues { it.size > 1 }
            .mapValues { (_, indexes) -> indexes.map { EpgTraditionalFold.TRADITIONAL_CHARS[it] }.toSet() }
        assertThat(collisions).hasSize(1)
        assertThat(collisions.values.single()).containsExactly('綫', '線')
    }

    @Test
    fun `folding is idempotent for every entry in the table`() {
        for (traditional in EpgTraditionalFold.TRADITIONAL_CHARS) {
            val once = EpgTraditionalFold.fold(traditional.toString())
            val twice = EpgTraditionalFold.fold(once)
            assertThat(twice).isEqualTo(once)
        }
    }

    @Test
    fun `folding keeps the length and the character order of the name`() {
        // The structural guarantee behind "a wrong match is worse than a miss": a per-character
        // substitution cannot shorten a name, so it cannot make two different-length names equal.
        val names = listOf("鳳凰衛視中文台", "無綫新聞台", "中央電視台記錄頻道", "澳視澳門", "中天新聞")
        for (name in names) {
            val folded = EpgTraditionalFold.fold(name)
            assertThat(folded.length).isEqualTo(name.length)
            assertThat(folded).isNotEmpty()
        }
    }

    @Test
    fun `a simplified name is returned untouched`() {
        assertThat(EpgTraditionalFold.hasTraditional("凤凰卫视中文台")).isFalse()
        assertThat(EpgTraditionalFold.fold("凤凰卫视中文台")).isEqualTo("凤凰卫视中文台")
        assertThat(EpgTraditionalFold.fold("CCTV-13 新闻")).isEqualTo("CCTV-13 新闻")
        assertThat(EpgTraditionalFold.fold("")).isEmpty()
    }

    @Test
    fun `broadcast names fold the way the shipped guides spell them`() {
        assertThat(EpgTraditionalFold.fold("鳳凰衛視中文台")).isEqualTo("凤凰卫视中文台")
        assertThat(EpgTraditionalFold.fold("亞洲新聞台")).isEqualTo("亚洲新闻台")
        assertThat(EpgTraditionalFold.fold("無綫新聞台")).isEqualTo("无线新闻台")
        assertThat(EpgTraditionalFold.fold("中央電視台新聞頻道")).isEqualTo("中央电视台新闻频道")
        assertThat(EpgTraditionalFold.fold("澳視澳門")).isEqualTo("澳视澳门")
        assertThat(EpgTraditionalFold.fold("中天新聞")).isEqualTo("中天新闻")
        assertThat(EpgTraditionalFold.fold("TVB星河頻道")).isEqualTo("TVB星河频道")
        // Characters that are the same in both scripts must come through unchanged (星河, 明珠, 翡翠).
        assertThat(EpgTraditionalFold.fold("明珠台")).isEqualTo("明珠台")
        assertThat(EpgTraditionalFold.fold("翡翠台")).isEqualTo("翡翠台")
        assertThat(EpgTraditionalFold.fold("CCTV1 標清")).isEqualTo("CCTV1 标清")
    }

    @Test
    fun `names of different channels do not fold onto each other`() {
        val pairs = listOf(
            "中天綜合台" to "中天娛樂台",
            "中天亞洲台" to "中天新聞",
            "翡翠台" to "黃金翡翠台",
            "無綫新聞台" to "無綫財經台",
            "三立新聞台" to "三立財經新聞台",
        )
        for ((left, right) in pairs) {
            assertThat(EpgTraditionalFold.fold(left)).isNotEqualTo(EpgTraditionalFold.fold(right))
        }
    }

    @Test
    fun `the margin characters the corpus did not exercise are covered`() {
        // 標清 is a feed marker, 導視 a channel name, 縣 the county-level stations: each is a shape a
        // guide or a playlist can use tomorrow, and each folds by the same rule rather than by a new
        // alias entry.
        assertThat(EpgTraditionalFold.fold("標清")).isEqualTo("标清")
        assertThat(EpgTraditionalFold.fold("導視")).isEqualTo("导视")
        assertThat(EpgTraditionalFold.fold("臺北縣")).isEqualTo("台北县")
        // 臺 is the one margin character a Taiwan guide may well spell the Traditional way tomorrow.
        assertThat(EpgTraditionalFold.fold("臺視")).isEqualTo("台视")
    }
}
