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
    const val EPGSHARE01_HK = "epgshare01.hk"

    /**
     * Two independent public guides, deliberately from different projects: one source having a bad
     * day (or dropping a country) is normal, and a single-source EPG is a single point of failure.
     * Both are plain XMLTV; the fetcher sniffing gzip means a `.xml.gz` upstream keeps working.
     *
     * Both addresses were reached from this machine on 2026-09-22 (the sample run in
     * `docs/05-过程记录/27-P2-7EPG验证.md` reports what came back). The iptv-org guide host that the
     * docs' F6 sketch implies is **gone** (`https://iptv-org.github.io/epg/guides/cn.xml` → 404), so
     * it is not listed: a built-in catalogue is a promise that the address works.
     */
    val DEFAULT_CATALOG: List<EpgSourceDescriptor> = listOf(
        EpgSourceDescriptor(
            id = EPG_PW_CN,
            label = "epg.pw (CN)",
            url = "https://epg.pw/xmltv/epg_CN.xml",
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
