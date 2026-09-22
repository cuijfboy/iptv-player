package ilab.iptv.player.core.epg

/**
 * The alias tier of docs/02 §6.3's match chain (④): names our playlist uses that the guide spells
 * differently, which neither `tvg-id` nor a normalized-name comparison can bridge.
 *
 * **What this table is for (P3-5 maintenance rules).** Every entry has to earn its place:
 *
 * 1. **One entry = one real miss.** A key is added only after a real playlist name failed every
 *    mechanical tier in a real sample (`-Piptv.epgSample=1` prints the top misses per group), never
 *    from imagination. The sample that produced the entries below is
 *    `docs/05-过程记录/37-P3-5EPG覆盖率.md` §3/§4.
 * 2. **Mechanical before semantic.** If the difference is punctuation/a feed marker/a `+`, it belongs
 *    in [EpgNameVariants] (a rule, applied to both sides), not here. An entry here means the two
 *    names are the *same channel* by knowledge, not by spelling (`央视新闻` is CCTV-13).
 * 3. **Never bridge two channels.** `CCTV5` / `CCTV5+` / `CCTV4K` are different channels; an entry
 *    that maps one to another is a wrong match, and a wrong match is worse than a miss (§6.3).
 * 4. **A value is an XMLTV channel id *or* a guide display name.** Name-shaped targets are preferred
 *    for public guides, whose ids are per-source numbers (`539631`) that change when the source
 *    re-scrapes. A name-shaped target resolves through the guide's own index and through
 *    [EpgNameVariants], so it survives a guide that adds ` 高清`.
 * 5. **Keep it small.** A large table is a signal that a rule is missing; if a new shape keeps
 *    appearing (`X 高清`, `X-Plus`), add the rule instead of N entries.
 *
 * A user-edited binding (`EpgMatchType.MANUAL`) always outranks this table and is never overwritten
 * (§6.3); the matcher enforces that, this type does not need to.
 */
class EpgAliases(entries: Map<String, String> = emptyMap()) {

    private val byNameKey: Map<String, String> = entries.mapKeys { (key, _) -> EpgNameKey.key(key) }

    val size: Int get() = byNameKey.size

    /** The raw alias target for a channel name, or null. Keys are normalized on both sides. */
    fun targetFor(channelName: String?): String? = byNameKey[EpgNameKey.key(channelName)]

    companion object {
        /** An empty table — the honest default for a test that does not want the seed in the way. */
        val EMPTY = EpgAliases()

        /**
         * The shipped table. Three groups, each with the evidence that put it there:
         *
         * - **seed (P2-7)** — the first ten entries only existed to make the tier real and testable;
         *   `湖南卫视-高清` and friends are now redundant (tier 3 folds feed markers) but are kept
         *   because they also cover sources whose guide drops the marker in a third way.
         * - **央视X → 编号名 (P3-5)** — the 16 highest-frequency misses of the shipped fixture: the
         *   playlist spells the channel by its common name (`央视新闻`), the guide by its number
         *   (`CCTV-13 新闻`). Not mechanical: the number is knowledge, so it is a table entry.
         * - **regional/traditional forms (P3-5)** — `福建东南卫视` is the guide's `东南卫视`;
         *   the guide writes 凤凰 in its short form (`凤凰中文`, `凤凰资讯`).
         *
         * Every target below was read back out of a live guide on 2026-09-22 (`epg.pw` CN); the row
         * count is asserted in `EpgMatcherTest` so growth has to be a decision.
         */
        val BUILT_IN: EpgAliases = EpgAliases(
            mapOf(
                // P2-7 seed — the shapes the tier was built for.
                "CCTV-1 综合" to "CCTV1",
                "CCTV-2 财经" to "CCTV2",
                "CCTV-5 体育" to "CCTV5",
                // The `+` channel: folding covers guides that carry a plain `CCTV5+`, and this entry
                // covers the ones that only carry the long form. (The target has to be a *name*: the
                // public guides' ids are per-source numbers, and the old `cctv5plus` id resolved to
                // nothing anywhere.)
                "CCTV5+" to "CCTV-5+ 体育赛事",
                "CCTV-6 电影" to "CCTV6",
                "CCTV-13 新闻" to "CCTV13",
                "湖南卫视-高清" to "湖南卫视",
                "浙江卫视-高清" to "浙江卫视",
                "东方卫视-高清" to "东方卫视",
                // P3-5: the playlist's common name → the guide's numbered name (16 real misses).
                "央视新闻" to "CCTV-13 新闻",
                "央视财经" to "CCTV-2 财经",
                "央视综艺" to "CCTV-3 综艺",
                "央视体育" to "CCTV-5 体育",
                "央视电影" to "CCTV-6 电影",
                "央视军事" to "CCTV-7 国防军事",
                "央视电视剧" to "CCTV-8 电视剧",
                "央视纪录" to "CCTV-9 纪录",
                "央视科教" to "CCTV-10 科教",
                "央视戏曲" to "CCTV-11 戏曲",
                "央视社会与法" to "CCTV-12 社会与法",
                "央视少儿" to "CCTV-14 少儿",
                "央视音乐" to "CCTV-15 音乐",
                "央视农业" to "CCTV-17农业农村",
                "央视奥运" to "CCTV-16奥林匹克",
                // The playlist distinguishes 中文国际 / 亚洲; the public guide only carries 亚洲.
                "CCTV-4 中文国际" to "CCTV-4 (亚洲)",
                // P3-5: regional + short forms (3 real misses).
                "福建东南卫视" to "东南卫视",
                "凤凰卫视中文台" to "凤凰中文",
                "凤凰卫视资讯台" to "凤凰资讯",
            ),
        )
    }
}
