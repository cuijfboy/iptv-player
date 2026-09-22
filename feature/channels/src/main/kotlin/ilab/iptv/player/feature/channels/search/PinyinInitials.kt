package ilab.iptv.player.feature.channels.search

/**
 * 拼音首字母 (P3-2: "输入用遥控数字/方向键的最小可用方案…可考虑首字母筛选 + 拼音首字母映射").
 *
 * WHAT IT IS: a **seeded** Hanzi → initial table for the characters this product's names are made of
 * (~170 characters: the 央视/卫视/地方台 families plus the words that recur in programme titles). It
 * makes "xw" find 「新闻联播」 and "hnws" find 「湖南卫视」 without shipping a full pinyin dictionary.
 *
 * WHAT IT IS NOT: not a complete pinyin table and not reading-aware. A character missing from the
 * table contributes nothing to the initials key (the name/substring match still covers it), and a
 * polyphonic character uses the reading these names use most (乐→y as in 音乐, 重→c as in 重庆/重播).
 * Both limits are stated in the verification record §5 rather than hidden.
 */
object PinyinInitials {

    /**
     * Alternating `汉字` + ASCII initial. Kept as one string (rather than ~170 map literals) so the
     * table reads like a table and a wrong letter sits next to the character it belongs to.
     */
    private const val TABLE =
        "中z央y电d视s台t新x闻w综z合h频p道d体t育y影y剧j场c少s儿e纪j录l科k教j军j事s农n业y财c经j" +
            "生s活h健j康k旅l游y美m食s动d画h艺y春c晚w大d赛s直z播b重c预y告g国g际j东d方f卫w北b京j" +
            "上s海h天t津j庆q湖h南n浙z江j苏s广g深s圳z安a徽h河h山s西x云y贵g四s川c福f建j辽l宁n" +
            "吉j林l黑h龙l蒙m古g疆j甘g肃s青q夏x陕s湾w香x港g澳a门m华h人r民m公g幼y市s县x区q镇z" +
            "唐t宋s明m清q传c奇q武w侠x神s话h童t卡k通t音y院y线x娱y星x空k坛t谈t访f法f制z治z聚j焦j" +
            "时s政z论l气q报b交j汽q车c房f产c家j居j装z修x尚s购g物w服f女n男n亲q子z讲j座z戏x曲q" +
            "相x声s小x品p联l欢h热r点d注z特t别b专z题t栏l目m节j片p花h絮x幕m后h"

    private val initials: Map<Char, Char> = parse(TABLE)

    /**
     * The initials of [text]: ASCII letters and digits are kept as typed, Hanzi contribute their
     * table entry, and everything else (punctuation, unknown Hanzi) is skipped. So 「CCTV1 综合」
     * becomes "cctv1zh" and 「湖南卫视」 becomes "hnws".
     */
    fun initialsOf(text: String): String {
        val out = StringBuilder(text.length)
        text.forEach { raw ->
            val ch = raw.lowercaseChar()
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> out.append(ch)
                else -> initials[raw]?.let { out.append(it) }
            }
        }
        return out.toString()
    }

    /** How many characters the seeded table knows — reported by the search screen's status line. */
    fun size(): Int = initials.size

    private fun parse(table: String): Map<Char, Char> {
        val map = HashMap<Char, Char>()
        var pending: Char? = null
        table.forEach { ch ->
            if (ch in 'a'..'z') {
                pending?.let { map[it] = ch }
                pending = null
            } else {
                pending = ch
            }
        }
        return map
    }
}
