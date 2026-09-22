package ilab.iptv.player.core.epg

/**
 * The **mechanical** differences of docs/02 §6.3's match chain between how a playlist spells a
 * channel and how a guide spells the same channel: added by P3-5 (feed markers, punctuation, `+`) and
 * extended by the 繁简折叠 round (`docs/05-过程记录/39-EPG繁简折叠.md`).
 *
 * The P2-7 chain compared `EpgNameKey.key(name)` for equality, which only bridges width/space/case.
 * Real playlists and real guides also disagree about:
 *
 * - **feed markers**: `CCTV1 高清` / `CCTV1 标清` / `湖南卫视 HD` are the *same* channel as
 *   `CCTV1` / `湖南卫视` (a quality variant of one channel, not a second channel);
 * - **punctuation**: `CCTV-1 综合` vs `CCTV1综合`, `CCTV-13新闻` vs `CCTV-13 新闻`,
 *   `凤凰卫视-中文台` vs `凤凰卫视中文台`;
 * - **the `+` in CCTV-5+**, which guides write as `CCTV-5+`, `CCTV5+` or `CCTV5PLUS`.
 * - **script**: Hong Kong and Taiwan guides write Traditional (`鳳凰衛視中文台`, `澳視澳門`,
 *   `無綫新聞台`) while mainland playlists write Simplified — the fold is [EpgTraditionalFold], a
 *   character table, applied to both sides like every other rule here.
 * - **the numbered handle** (EPG-BIND, `docs/05-过程记录/43-EPG匹配层候选化.md`): the mainland guide
 *   writes the CCTV channels by their *column* name (`CCTV-2 财经`, `CCTV-3 综艺`), the playlist by
 *   the bare number (`CCTV2`), and the same guide also declares a **zero-programme stub** under the
 *   bare number. `CCTV2` and `CCTV-2 财经` are one channel, so the rule reduces both to the handle
 *   `cctv2` and the multi-id index offers every id under it to the binding choice.
 *
 * Each rule is applied to **both** sides (a guide name is expanded into the same variant set), so
 * the tier is still an equality test — it is not the "前缀/包含模糊" of §6.3 tier ③, which P2-7
 * deferred to P3-5 and this round still leaves out: a fuzzy match that is wrong is worse than a
 * miss, and the measured gain on the shipped fixture is 4 channels (all in the synthetic local
 * bucket). See `docs/05-过程记录/37-P3-5EPG覆盖率.md` §5.
 *
 * **What is deliberately not stripped** — the markers that *do* identify a different channel:
 * `4k`, `8k`, `uhd`, `sd` and a trailing `+`/`plus` (`CCTV4` vs `CCTV4K`, `CCTV5` vs `CCTV5+` are
 * two different channels; folding them would bind a channel to its neighbour's programmes). This is
 * the "错配比不匹配更糟" rule of §6.3, and it has a unit test
 * (`EpgNameVariantsTest.a quality marker that identifies a different channel is never stripped`).
 *
 * **The order the rules run in** (part of the contract, not an implementation detail — the reported
 * `matchedOn` is the *first* variant that hits, so the order decides how a hit is explained):
 *
 * 1. **[EpgTraditionalFold]** — script first. It is a per-character substitution, so it neither
 *    depends on nor interferes with the other three, and running it first means a marker written the
 *    Traditional way (`標清`) is already `标清` when rule 4 looks for suffixes.
 * 2. **`+` → `plus`** — before punctuation removal, and `+` is deliberately not *in* the punctuation
 *    set, so the marker survives as a word instead of vanishing (`CCTV5+` must not become `CCTV5`).
 * 3. **punctuation removal** — hyphens, spaces-that-look-like-punctuation, brackets, quotes.
 * 4. **feed-marker stripping** — repeatedly, longest suffix first (see [QUALITY_SUFFIXES]).
 * 5. **numbered handle** ([handle]) — `cctv-2财经` → `cctv2`, `cctv-5+体育赛事` → `cctv5+`. Added
 *    last, and computed from the **raw key** (and its script fold) rather than from the other four
 *    rules' output: it fires only on a `cctv<number>` head followed by a Chinese descriptor or the
 *    end of the key, so `CCTV4K`/`CCTV-8K` (Latin tail — a different channel) and `CCTV-4 (亚洲)`
 *    (bracketed tail — a different feed) are left alone. Deriving it from a punctuation-stripped form
 *    would defeat that: `cctv-4(亚洲)` loses its brackets before the handle could refuse them.
 */
object EpgNameVariants {

    /**
     * Feed/quality markers a guide drops. Ordered longest-first (`fhd` before `hd`, `高清` before
     * nothing that could halve it): a shorter suffix must never eat the tail of a longer one and leave
     * a stray character behind (`cctv1fhd` → `cctv1f` would then match nothing and report a miss).
     * `4k` / `8k` / `uhd` / `sd` / `plus` are **absent on purpose** (a different channel, see above).
     */
    private val QUALITY_SUFFIXES: List<String> =
        listOf("高码", "高清", "标清", "超清", "蓝光", "fhd", "hd")

    /**
     * Punctuation playlists and guides disagree on. `+` is **not** here — it is folded to `plus`
     * instead, because dropping it would turn `CCTV5+` into `CCTV5` (a different channel).
     */
    private val PUNCTUATION: Set<Char> =
        "-_·.。，,、;；:：!！'’“”\"()（）[]【】{}<>《》/|｜".toSet()

    private const val PLUS = "plus"

    /**
     * The most aggressive form: punctuation removed, `+` folded to `plus`, feed markers stripped,
     * all of it on top of the Traditional→Simplified fold, applied in the order documented on this
     * object. Two keys with the same canonical form are the same channel as far as this round can tell.
     */
    fun canonical(key: String): String =
        stripQuality(stripPunctuation(foldPlus(EpgTraditionalFold.fold(key))))

    /**
     * Every form of `key` the matcher is allowed to look up, least-mutated first (so the reported
     * `matchedOn` is the smallest change that explains the hit). Empty return for an empty key.
     *
     * The first sixteen forms are every subset of the four normalization rules, applied in the order
     * documented on this object (script → `+` → punctuation → feed marker), enumerated with the script
     * fold as the **highest** bit: all non-script forms keep the position they had before the fold
     * existed, so a name with no Traditional character produces exactly the variants it produced in
     * P3-5, in the same order. Rule 5's numbered handle is appended after them (see [handle]).
     */
    fun variants(key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val simplified = EpgTraditionalFold.fold(key)
        val out = LinkedHashSet<String>(18)
        // script (bit 3) > feed marker (bit 2) > `+` (bit 1) > punctuation (bit 0), ascending mask:
        // the least-mutated form first, the fully canonicalized form last.
        for (mask in 0 until 16) {
            var form = if (mask and 8 != 0) simplified else key
            if (mask and 2 != 0) form = foldPlus(form)
            if (mask and 1 != 0) form = stripPunctuation(form)
            if (mask and 4 != 0) form = stripQuality(form)
            if (form.isNotEmpty()) out += form
        }
        // The numbered handle is a second identity, not another normalization combination, so it is
        // computed from the key itself. It is appended last (the heaviest change) and removes itself
        // when the key already is its own handle, which is why a key the rule cannot touch keeps
        // exactly the sixteen forms — and the order — it had before the rule existed.
        out += handle(key)
        out += handle(simplified)
        return out.toList()
    }

    /** One variant key → one guide channel it stands for, and the guide key it was derived from. */
    data class VariantHit(val epgChannelId: String, val guideKey: String)

    /**
     * Expands every guide name key into its variant forms, keeping **every** channel id each variant
     * stands for. `byNameKey` is the guide-order map built by [epgChannelIndex], so the result is
     * deterministic: a guide that lists `CCTV-2 财经` and then a `CCTV2` stub yields `cctv2` →
     * `[545933, 561310]`, in that order, and the binding choice picks the one with programmes.
     *
     * Several ids under one variant is the norm on a real guide (EPG-BIND): `CCTV2` = `561310` has
     * zero programmes while `CCTV-2 财经` = `545933` has 274, and the same-held name is declared more
     * than once for 52 groups (e.g. `东方卫视`). A single-winner map cannot represent either.
     */
    fun index(byNameKey: Map<String, List<String>>): Map<String, List<VariantHit>> {
        val out = HashMap<String, MutableList<VariantHit>>(byNameKey.size * 4)
        for ((key, ids) in byNameKey) {
            for (variant in variants(key)) {
                val bucket = out.getOrPut(variant) { ArrayList(2) }
                for (id in ids) {
                    // One id per variant, first guide key wins — the id is what the matcher collects,
                    // and a duplicate would only be deduplicated again downstream.
                    if (bucket.none { it.epgChannelId == id }) {
                        bucket += VariantHit(epgChannelId = id, guideKey = key)
                    }
                }
            }
        }
        return out
    }

    /**
     * The numbered handle of a `cctv`-headed key: `cctv2`, `cctv-2财经`, `cctv-16奥林匹克` → `cctv2`,
     * `cctv-16`; `cctv5+` / `cctv-5+体育赛事` → `cctv5+`. A key the rule cannot reduce is returned
     * unchanged, so it contributes no new variant.
     *
     * Two deliberate refusals, both the "错配比不匹配更糟" rule of §6.3:
     * - a **Latin** tail means a different channel: `CCTV4K` / `CCTV-8K` keep their `k` (they are the
     *   mirror of the [QUALITY_SUFFIXES] omission of `4k`/`8k`);
     * - a **bracketed** tail is a region marker the guide uses for genuinely different feeds:
     *   `CCTV-4 (亚洲)` / `CCTV-4 (欧洲)` / `CCTV-4 (美洲)` do not collapse to `cctv4`.
     * The remaining exposed shape is a bare Chinese region suffix with no brackets (`CCTV4美洲`); it
     * does fold to `cctv4`, which the binding choice resolves by depth. Flagged for arch in the round
     * record — a rule that reads *which* Chinese word it is would be knowledge, not mechanics.
     */
    private fun handle(key: String): String {
        if (key.length < 5 || !key.startsWith("cctv")) return key
        var index = 4
        if (index < key.length && (key[index] == '-' || key[index] == '_')) index++
        val digits = StringBuilder(3)
        while (index < key.length && key[index] in '0'..'9') {
            digits.append(key[index])
            index++
        }
        if (digits.isEmpty()) return key
        val out = StringBuilder(8).append("cctv").append(digits)
        if (index < key.length && key[index] == '+') {
            out.append('+')
            index++
        }
        if (index >= key.length) return out.toString()
        return if (isCjk(key[index])) out.toString() else key
    }

    private fun isCjk(ch: Char): Boolean = ch.code in 0x3400..0x9FFF

    private fun foldPlus(key: String): String {
        if ('+' !in key && '＋' !in key) return key
        val out = StringBuilder(key.length + 4)
        for (ch in key) {
            if (ch == '+' || ch == '＋') out.append(PLUS) else out.append(ch)
        }
        return out.toString()
    }

    private fun stripPunctuation(key: String): String {
        if (key.none { it in PUNCTUATION }) return key
        val out = StringBuilder(key.length)
        for (ch in key) if (ch !in PUNCTUATION) out.append(ch)
        return out.toString()
    }

    /** Strips feed markers repeatedly (`CCTV1 高清 HD` → `cctv1`), never below a non-empty key. */
    private fun stripQuality(key: String): String {
        var out = key
        var changed = true
        while (changed) {
            changed = false
            for (suffix in QUALITY_SUFFIXES) {
                if (out.length > suffix.length && out.endsWith(suffix)) {
                    out = out.substring(0, out.length - suffix.length)
                    changed = true
                }
            }
        }
        return out
    }
}
