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
     * The set is every subset of the four rules, applied in the order documented on this object
     * (script → `+` → punctuation → feed marker), enumerated with the script fold as the **highest**
     * bit: all non-script forms keep the position they had before the fold existed, so a name with no
     * Traditional character produces exactly the variants it produced in P3-5, in the same order.
     */
    fun variants(key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val simplified = EpgTraditionalFold.fold(key)
        val out = LinkedHashSet<String>(16)
        // script (bit 3) > feed marker (bit 2) > `+` (bit 1) > punctuation (bit 0), ascending mask:
        // the least-mutated form first, the fully canonicalized form last.
        for (mask in 0 until 16) {
            var form = if (mask and 8 != 0) simplified else key
            if (mask and 2 != 0) form = foldPlus(form)
            if (mask and 1 != 0) form = stripPunctuation(form)
            if (mask and 4 != 0) form = stripQuality(form)
            if (form.isNotEmpty()) out += form
        }
        return out.toList()
    }

    /** One variant key → the guide channel it stands for, and the guide key it was derived from. */
    data class VariantHit(val epgChannelId: String, val guideKey: String)

    /**
     * Expands every guide name key into its variant forms. First writer wins, and `byNameKey` is the
     * guide-order map built by [epgChannelIndex], so the result is deterministic: a guide that lists
     * `CCTV1` before `CCTV1 HD` keeps `cctv1` pointing at the first one.
     */
    fun index(byNameKey: Map<String, String>): Map<String, VariantHit> {
        val out = HashMap<String, VariantHit>(byNameKey.size * 4)
        for ((key, id) in byNameKey) {
            for (variant in variants(key)) {
                // `putIfAbsent` is API 24 and this module supports API 21 (docs/02 §14).
                if (variant !in out) out[variant] = VariantHit(epgChannelId = id, guideKey = key)
            }
        }
        return out
    }

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
