package ilab.iptv.player.core.model

/**
 * The EPG types of docs/02 §4.2 that are pure data (the streaming handle itself lives in
 * `:core:epg`, next to the provider that produces it — see `XmltvStream` there).
 *
 * `Programme`, `NowNext` and `EpgMatchType` already live in `Channel.kt`; this file adds the
 * reporting shapes the §4.3 ports and the diagnostics panel need.
 */

/**
 * docs/02 §6.3: "覆盖率可观测". `matched` counts channels that carry an `epg_channel_id` after a
 * match, `total` every channel considered, and `byGroup` breaks the matched count down by the
 * coarse five-value group the diagnostics panel shows.
 *
 * **Two readings of "covered", because they are not the same thing** (EPG-TRAD-1 exposed it):
 * [matched] answers "does the channel have a guide id", [withProgrammes] answers "does that id
 * actually hold at least one programme inside the retention window". A guide can declare a
 * `<channel>` and publish no `<programme>` for it — the channel then has a perfectly good id, an
 * empty grid, and used to be counted as covered. [emptyBinding] is the difference; [programmedRatio]
 * is the reading that tells the truth, [ratio] is kept as the pre-EPG-BIND口径 so the shape stays
 * backward compatible.
 */
data class EpgCoverage(
    val matched: Int,
    val total: Int,
    val byGroup: Map<ChannelGroup, Int>,
    /**
     * How many channels each group *has*, so [byGroup] can be read as `42/80 央视` instead of a bare
     * numerator. P3-5 added it: docs/04's P3-5 exit is "≥60%（主流频道）", and a per-group ratio is
     * not computable from the matched counts alone. Defaults to empty for producers that only know the
     * matched side (older tests, a partial index).
     */
    val byGroupTotal: Map<ChannelGroup, Int> = emptyMap(),
    /**
     * Channels among [matched] whose guide id holds ≥1 programme inside the retention window.
     * Defaults to [matched]: a producer that only knows the id side is read the old way ("every
     * binding has programmes"), so its numbers do not change.
     */
    val withProgrammes: Int = matched,
    /** [withProgrammes] broken down the same way [byGroup] breaks [matched] down. */
    val byGroupWithProgrammes: Map<ChannelGroup, Int> = emptyMap(),
) {
    /** Share of channels with a programme table, 0..1; 0 when there is nothing to cover. */
    val ratio: Double get() = if (total <= 0) 0.0 else matched.toDouble() / total

    /** Matched channels whose binding holds nothing in the window — "covered" on paper, blank in the app. */
    val emptyBinding: Int get() = (matched - withProgrammes).coerceAtLeast(0)

    /**
     * Share of channels that can actually show something, 0..1. This is the reading the target gate
     * and the diagnostics panel use: an empty binding must not make the number look better.
     */
    val programmedRatio: Double get() = if (total <= 0) 0.0 else withProgrammes.toDouble() / total
}

/**
 * docs/02 §4.3 / §8.3: the grid's window read. `limit` defaults to the frozen 64 channels of a grid
 * page — the cap exists so one query can never ask the database for 1k channels × 7 days.
 */
data class EpgWindowQuery(
    val fromMs: Long,
    val toMs: Long,
    val channelIds: List<Long>,
    val limit: Int = 64,
)

/**
 * What one EPG refresh did (docs/02 §4.2). `skipped` counts `<programme>` entries the parser dropped
 * (malformed dates, no title, outside the retention window) — a number worth reporting, because a
 * source that suddenly skips half its rows is broken, not "empty".
 *
 * [interrupted] is set when the run stopped early on purpose instead of failing: the only such reason
 * today is `playback_priority` (P3-6's run-time half of R7 — a session started playing while the
 * guides were being pulled, so the remaining sources were left for the next run). The rows already
 * written stay; §6.3's degradation rule makes that a valid partial result.
 */
data class EpgLoadReport(
    val providers: Int,
    val channels: Int,
    val programmes: Int,
    val skipped: Int,
    val coverage: EpgCoverage,
    val elapsedMs: Long,
    val interrupted: String? = null,
)

/**
 * What the `epg_source` table says about EPG right now (P3-6). The diagnostics panel reads it to
 * answer "when did EPG last run, and did it work"; the P3-6 trigger gate reads [lastFetchAtMs] to
 * answer "is what we have still fresh enough to skip a wake-up".
 *
 * Read-only status columns docs/02 §5.1 already keeps on `epg_source` (`last_fetch_at` /
 * `last_result`); nothing new is stored for it.
 */
data class EpgSourceStatus(
    val sources: Int,
    val enabledSources: Int,
    /** Newest `last_fetch_at` across the rows, null when nothing ever ran. */
    val lastFetchAtMs: Long?,
    /** The `last_result` of that newest row, e.g. `OK:25234` or `FAIL:TIMEOUT`. */
    val lastResult: String?,
)

/**
 * docs/02 §4.4 E5: the lookup a [ilab.iptv.player.core.model.Channel] is matched against.
 *
 * `byId` is keyed on the *source's* channel attribute (`tvg-id`), `byNameKey` on the normalized
 * display name of an XMLTV `<channel>` — exactly the two keys the first two match tiers need. Both
 * map to the XMLTV channel id, which is what `programme.epg_channel_id` stores.
 */
data class EpgChannelIndex(
    val byId: Map<String, String> = emptyMap(),
    val byNameKey: Map<String, String> = emptyMap(),
) {
    val size: Int get() = byId.size + byNameKey.size
}
