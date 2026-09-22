package ilab.iptv.player.core.epg

/**
 * The built-in XMLTV sources (docs/02 §6.3 step 1: "默认公共源，可在设置里配置").
 *
 * **Addresses only — no channel and no programme data.** The job's hard rule is that the repository
 * carries aggregate *list addresses* and nothing else: the same discipline as
 * `:core:source`'s `BuiltInSources`, where the catalogue of 17 aggregates is URLs, not streams.
 * These two endpoints serve per-country XMLTV guides; which channels they contain is whatever the
 * upstream project publishes today, and it is fetched at runtime, never committed.
 *
 * The user-visible source list is the `epg_source` table (docs/02 §5.1): rows present there win, and
 * [DEFAULT_CATALOG] is the seed when the table is empty. Editing/disabling a row is P2-6 settings
 * work; this round only reads it.
 */
data class EpgSourceDescriptor(
    val id: String,
    val label: String,
    val url: String,
    /** Shown as the source's kind in the diagnostics panel; the body is sniffed regardless. */
    val gzipLikely: Boolean = false,
) {
    fun toEntityRow(enabled: Boolean = true): EpgSourceRow =
        EpgSourceRow(id = id, label = label, url = url, enabled = enabled)
}

/** The config shape the refresh reads: what the table (or the seed) says about one EPG source. */
data class EpgSourceRow(
    val id: String,
    val label: String,
    val url: String,
    val enabled: Boolean,
)

object BuiltInEpgSources {

    const val EPG_PW_CN = "epg.pw.cn"
    const val EPG_PW_HK = "epg.pw.hk"
    const val EPG_PW_TW = "epg.pw.tw"
    const val EPGSHARE01_HK = "epgshare01.hk"

    /**
     * Four public guides. Two projects (epg.pw, epgshare01) so one having a bad day is not a single
     * point of failure, and four guides because each covers a different slice: mainland (CN), Hong
     * Kong (epgshare01 HK + epg.pw HK), Taiwan (TW); the fixture's 港澳台 channels are only complete
     * once the HK/TW guides are in. All are plain XMLTV; the fetcher sniffs gzip by magic bytes, so a
     * `.xml.gz` upstream keeps working without a per-source flag — and where the same guide is served
     * both ways the `.gz` address is listed, because it is the same document 20× smaller
     * (`epg_CN.xml` 6.3 MB → `epg_CN.xml.gz` 307 KB, measured 2026-09-22).
     *
     * Every address was reached from this machine on 2026-09-22 (the sample run in
     * `docs/05-过程记录/37-P3-5EPG覆盖率.md` reports what came back). The iptv-org guide host that the
     * docs' F6 sketch implies is **gone** (`https://iptv-org.github.io/epg/guides/cn.xml` → 404), so
     * it is not listed: a built-in catalogue is a promise that the address works.
     */
    val DEFAULT_CATALOG: List<EpgSourceDescriptor> = listOf(
        EpgSourceDescriptor(
            id = EPG_PW_CN,
            label = "epg.pw (CN)",
            url = "https://epg.pw/xmltv/epg_CN.xml.gz",
            gzipLikely = true,
        ),
        EpgSourceDescriptor(
            id = EPG_PW_HK,
            label = "epg.pw (HK)",
            url = "https://epg.pw/xmltv/epg_HK.xml.gz",
            gzipLikely = true,
        ),
        EpgSourceDescriptor(
            id = EPG_PW_TW,
            label = "epg.pw (TW)",
            url = "https://epg.pw/xmltv/epg_TW.xml.gz",
            gzipLikely = true,
        ),
        EpgSourceDescriptor(
            id = EPGSHARE01_HK,
            label = "epgshare01 (HK)",
            url = "https://epgshare01.online/epgshare01/epg_ripper_HK1.xml.gz",
            gzipLikely = true,
        ),
    )

    fun byId(id: String): EpgSourceDescriptor? = DEFAULT_CATALOG.firstOrNull { it.id == id }

    /** The seed rows, in catalogue order — what `epg_source` is filled with when it is empty. */
    fun seedRows(): List<EpgSourceRow> = DEFAULT_CATALOG.map { it.toEntityRow() }
}
