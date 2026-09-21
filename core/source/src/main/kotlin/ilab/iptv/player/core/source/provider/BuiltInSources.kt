package ilab.iptv.player.core.source.provider

import ilab.iptv.player.core.model.SourceConfig
import ilab.iptv.player.core.model.SourceKind

/**
 * One built-in aggregate source: identity plus where the list lives. No concrete *channel* URL ever
 * appears here — only aggregate list addresses and the dialect hint (the job's hard rule: do not
 * commit real stream URLs).
 */
data class SourceDescriptor(
    val id: String,
    val label: String,
    val kind: SourceKind,
    val url: String,
) {
    fun toConfig(): SourceConfig = SourceConfig(
        id = id,
        providerId = id,
        label = label,
        url = url,
        kind = kind,
        enabled = true,
        builtIn = true,
    )
}

/**
 * The 17 public aggregates of the Python baseline (`/Users/jeffrey/temp/dsh/iptv/collect_sources.py`),
 * copied as **list addresses only**. [SourceKind] is the declared dialect from the URL; the provider
 * still auto-detects from the body, so a list that changes extension keeps working.
 */
object BuiltInSources {

    const val GUOVIN_RESULT = "guovin.result"
    const val GUOVIN_IPV4 = "guovin.ipv4"
    const val FANMINGMING_ITV = "fanmingming.itv"
    const val FANMINGMING_INDEX = "fanmingming.index"
    const val YANG_GATHER = "yang.gather"
    const val YANG_MIGU = "yang.migu"
    const val HUJINGGUANG_CN = "hujingguang.cn"
    const val BESTFAN_CN_ALL = "bestfan.cn_all"
    const val BESTFAN_CN_CCTV = "bestfan.cn_cctv"
    const val BESTFAN_CN_PROVINCE = "bestfan.cn_province"
    const val AKIRALEREAL_IPTV = "akiralereal.iptv"
    const val EVILCULT_HTTP = "evilcult.http"
    const val VBSKYCN_IPTV4 = "vbskycn.iptv4"
    const val NGO5_IPV4 = "ngo5.ipv4"
    const val SUPPRISE_LIVE = "supprise.live"
    const val KIMWANG_OTHERS = "kimwang.others"
    const val SUXUANG_IPV4 = "suxuang.ipv4"

    /** The catalogue, in the baseline's order. Adding an aggregate is adding a row here. */
    val CATALOG: List<SourceDescriptor> = listOf(
        SourceDescriptor(
            GUOVIN_RESULT, "Guovin/result", SourceKind.M3U,
            "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u",
        ),
        SourceDescriptor(
            GUOVIN_IPV4, "Guovin/ipv4", SourceKind.M3U,
            "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/ipv4/result.m3u",
        ),
        SourceDescriptor(
            FANMINGMING_ITV, "fanmingming/itv", SourceKind.M3U,
            "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/itv.m3u",
        ),
        SourceDescriptor(
            FANMINGMING_INDEX, "fanmingming/index", SourceKind.M3U,
            "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/index.m3u",
        ),
        SourceDescriptor(
            YANG_GATHER, "YanG/Gather", SourceKind.M3U,
            "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u",
        ),
        SourceDescriptor(
            YANG_MIGU, "YanG/Migu", SourceKind.M3U,
            "https://raw.githubusercontent.com/YanG-1989/m3u/main/Migu.m3u",
        ),
        SourceDescriptor(
            HUJINGGUANG_CN, "hujingguang/cn", SourceKind.M3U,
            "https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV1_ALL.m3u8",
        ),
        SourceDescriptor(
            BESTFAN_CN_ALL, "bestfan/cn_all", SourceKind.M3U,
            "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8",
        ),
        SourceDescriptor(
            BESTFAN_CN_CCTV, "bestfan/cn_cctv", SourceKind.M3U,
            "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
        ),
        SourceDescriptor(
            BESTFAN_CN_PROVINCE, "bestfan/cn_province", SourceKind.M3U,
            "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
        ),
        SourceDescriptor(
            AKIRALEREAL_IPTV, "akiralereal/IPTV", SourceKind.M3U,
            "https://raw.githubusercontent.com/akiralereal/iptv/main/IPTV.m3u",
        ),
        SourceDescriptor(
            EVILCULT_HTTP, "EvilCult/http", SourceKind.M3U,
            "https://raw.githubusercontent.com/EvilCult/iptv-m3u-maker/master/http/tv.m3u",
        ),
        SourceDescriptor(
            VBSKYCN_IPTV4, "vbskycn/iptv4", SourceKind.M3U,
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
        ),
        SourceDescriptor(
            NGO5_IPV4, "ngo5/ipv4", SourceKind.M3U,
            "https://raw.githubusercontent.com/ngo5/IPTV/main/m3u/ipv4.m3u",
        ),
        SourceDescriptor(
            SUPPRISE_LIVE, "Supprise/live", SourceKind.TXT,
            "https://raw.githubusercontent.com/Supprise0901/TVBox_live/main/live.txt",
        ),
        SourceDescriptor(
            KIMWANG_OTHERS, "kimwang/others", SourceKind.TXT,
            "https://raw.githubusercontent.com/kimwang1978/collect-tv-txt/main/others/output.txt",
        ),
        SourceDescriptor(
            SUXUANG_IPV4, "suxuang/ipv4", SourceKind.M3U,
            "https://raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u",
        ),
    )

    fun byId(id: String): SourceDescriptor? = CATALOG.firstOrNull { it.id == id }
}
