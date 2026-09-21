package ilab.iptv.player.core.epg

/**
 * The alias tier of docs/02 §6.3's match chain (④): names our playlist uses that the guide spells
 * differently, which neither `tvg-id` nor a normalized-name comparison can bridge.
 *
 * **This is a seed, not the feature.** The job's boundary is explicit that large-scale alias
 * backfill is P3-5 ("不做别名表大规模补录"), whose target is ≥60% coverage across several sources.
 * What is here is the handful needed to make the tier real and testable: a key is
 * `EpgNameKey.key(channel name)`, and a value is either an XMLTV channel **id** or a name that is
 * looked up in the guide's own index — so a table entry keeps working when a source renames its
 * channel ids but keeps the names.
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
         * A few entries proving the tier, with the shapes real data has:
         * - a `tvg-id` the playlist carries differently from the guide's (`CCTV1` vs `CCTV-1`),
         * - a regional suffix the guide drops (`湖南卫视-高清` → `湖南卫视`),
         * - an id-shaped target (`cctv5plus`) next to a name-shaped one.
         */
        val BUILT_IN: EpgAliases = EpgAliases(
            mapOf(
                "CCTV-1 综合" to "CCTV1",
                "CCTV-2 财经" to "CCTV2",
                "CCTV-5 体育" to "CCTV5",
                "CCTV-5+ 体育赛事" to "cctv5plus",
                "CCTV-6 电影" to "CCTV6",
                "CCTV-13 新闻" to "CCTV13",
                "湖南卫视-高清" to "湖南卫视",
                "浙江卫视-高清" to "浙江卫视",
                "东方卫视-高清" to "东方卫视",
                "凤凰卫视中文台" to "凤凰卫视",
            ),
        )
    }
}
