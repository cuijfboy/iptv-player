package ilab.iptv.player.core.epg

/**
 * The **script** half of docs/02 §6.3's name normalization: Traditional → Simplified.
 *
 * **Why it exists.** P3-5 (`docs/05-过程记录/37-P3-5EPG覆盖率.md`) matched the fixture's 港澳台
 * channels with an *alias entry per channel* (`凤凰卫视中文台 → 凤凰中文`), because Hong Kong and
 * Taiwan guides spell names the way their viewers do (`鳳凰衛視中文台`, `澳視澳門`, `中天新聞`) while
 * mainland playlists spell them in Simplified. Aliases are the wrong tool for that: the difference is
 * script, not vocabulary, so it can be a **rule applied to both sides** instead of N entries — and a
 * rule is what makes the *next* Traditional guide name work without anyone noticing it was missing.
 *
 * **What it is not.** Folding never touches the guide's display name: `EpgChannelIndex` still carries
 * the guide's own key, and the matcher reports it in `EpgMatchResult.guideKey`, so a hit says *both*
 * spellings. Folding is a lookup key only (`EpgNameVariants`), never a value the user sees.
 *
 * **The table.** Character-level, one Traditional character → its Simplified form, and only
 * characters that real broadcast names actually use. It was derived, not typed from memory:
 *
 * 1. take every `<display-name>` of the four shipped guides (epg.pw CN/HK/TW + epgshare01 HK1, fetched
 *    2026-09-22) plus the 658 channel names of the shipped fixture;
 * 2. keep the characters whose Traditional→Simplified mapping differs —
 *    [OpenCC](https://github.com/BYVoid/OpenCC)'s `TSCharacters.txt` (Apache-2.0) is the source of
 *    that mapping, used **at build-table time only**;
 * 3. that yields **126 characters** (see [TRADITIONAL_CHARS]), plus four the corpus did not exercise
 *    but broadcast names use constantly (`標` in `標清`, `導` in `導視`, `縣` in county stations and
 *    `臺`, which today's guides happen to spell `台`) — kept in the table and marked as margin below.
 *
 * So: **no dependency is added** (no library, no asset, no generated resource — a Kotlin constant, so
 * the module stays pure Kotlin and `verifyPureKotlin` is untouched), and the whole table is ~800 bytes
 * of source. Every entry is a decision a reviewer can read; the mapping is regenerable with the three
 * steps above if a future guide introduces a character the table lacks.
 *
 * **Safety.** A per-character substitution can never delete a character, so it cannot collapse two
 * names of different lengths, and it can only merge two names that differ in characters which *are*
 * the same character in two scripts. The one merged pair inside the table is `綫`/`線` → `线`
 * (the same character, written two ways; TVB's `無綫新聞台` and `無線新聞台` are one channel).
 * `EpgTraditionalFoldTest` pins both the table's structure and the "must not merge" counter-examples.
 */
object EpgTraditionalFold {

    /**
     * The Traditional side of the table, one character per entry; index-aligned with
     * [SIMPLIFIED_CHARS]. Held as a string rather than a `Map` literal because 129 entries as a map is
     * 129 lines of noise around 129 characters of data, and the alignment is asserted by a test.
     */
    internal const val TRADITIONAL_CHARS: String =
        // Corpus: every differing character in the four guides' <display-name> list + the fixture.
        "亞來倫偵價優兒創劇動區嘆國園報場塢夢娛學實寵寶島峽廠廣廳強愛" +
            "戲戶搖數時會東業極樂檔歐歡歷測溫滾瀾灣無爾獎獻環畫當節粵紀紅" +
            "納絡經綜綫網線緝緯總聖聞聯聲興華萊藝蘇術衛裝視親訊記試話語誠" +
            "識豔豬貓財費資質購車軍遊運達選鉅錄鏡門間際雲電靂韓頻風養驚體" +
            "鳳麗麥黃點龍" +
            // Margin: not in the 2026-09-22 corpus, but common in broadcast names — 標清 (a feed
            // marker), 導視 / 某縣台, and 臺 (臺視 / 臺中), which both guides happen to spell 台 today.
            "標導縣臺"

    /** The Simplified side: `SIMPLIFIED_CHARS[i]` is what `TRADITIONAL_CHARS[i]` folds to. */
    internal const val SIMPLIFIED_CHARS: String =
        "亚来伦侦价优儿创剧动区叹国园报场坞梦娱学实宠宝岛峡厂广厅强爱" +
            "戏户摇数时会东业极乐档欧欢历测温滚澜湾无尔奖献环画当节粤纪红" +
            "纳络经综线网线缉纬总圣闻联声兴华莱艺苏术卫装视亲讯记试话语诚" +
            "识艳猪猫财费资质购车军游运达选巨录镜门间际云电雳韩频风养惊体" +
            "凤丽麦黄点龙" +
            "标导县台"

    private val TABLE: Map<Char, Char> = LinkedHashMap<Char, Char>(TRADITIONAL_CHARS.length).apply {
        for (index in TRADITIONAL_CHARS.indices) {
            this[TRADITIONAL_CHARS[index]] = SIMPLIFIED_CHARS[index]
        }
    }

    /** How many characters the table covers — the number a test (and the report) can quote. */
    val size: Int get() = TABLE.size

    /** True when [key] carries at least one character this table can fold. */
    fun hasTraditional(key: String): Boolean = key.any { it in TABLE }

    /**
     * Rewrites every Traditional character of [key] as its Simplified form; a key with nothing to fold
     * is returned **identical** (same instance is not promised, the same value is). Idempotent:
     * folding an already folded key changes nothing, which is asserted over the table itself.
     */
    fun fold(key: String): String {
        var out: StringBuilder? = null
        for (index in key.indices) {
            val ch = key[index]
            val folded = TABLE[ch] ?: ch
            if (out == null) {
                if (folded == ch) continue
                out = StringBuilder(key.length)
                out.append(key, 0, index)
            }
            out.append(folded)
        }
        return out?.toString() ?: key
    }
}
