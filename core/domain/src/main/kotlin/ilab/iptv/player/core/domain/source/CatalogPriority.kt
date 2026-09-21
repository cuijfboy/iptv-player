package ilab.iptv.player.core.domain.source

/** The two things that can write into the channel table. */
enum class CatalogWriter(val label: String) {
    /** The local-file import: `parse → CatalogSink.write` replaces the whole catalog. */
    LOCAL_IMPORT("本地导入"),

    /** A refresh run: `Fetch → … → Select → StreamRepository.upsertAll` updates existing rows only. */
    SUBSCRIPTION_REFRESH("订阅刷新"),
}

/**
 * Who wins when the import and the refresh both touch the catalog (P2-6 正篇 item 6: "导入与订阅的
 * 优先级规则（谁覆盖谁要写清）").
 *
 * The rule, in one sentence: **the local import owns the channel rows; a refresh owns the stream
 * rows of channels that already exist.**
 *
 * - `LOCAL_IMPORT` writes through `CatalogSink` — the contract of which is *replace*, so it is the
 *   only writer that may add or delete channels (docs/05-过程记录/19 §2 "导入即替换", INTEG-1).
 * - `SUBSCRIPTION_REFRESH` writes through `StreamRepository` — the P2-4 pipeline persists the
 *   *streams* of the channels it was given, so it can change health / score / `disabled` but cannot
 *   invent a channel. That is also why a subscription cannot "覆盖" an import's channel list: it has
 *   no channel-writing end at all.
 *
 * The two consequences a reader needs:
 * 1. importing a playlist **replaces** whatever a previous refresh had written (the sink is
 *    replace, not merge);
 * 2. a refresh after an import **does not duplicate or reorder** the imported channels — it only
 *    re-verifies and re-scores their streams.
 *
 * Known gap, reported to god (P2-6 §7): because the refresh pipeline has no channel-writing end yet,
 * channels that exist *only* in a subscription URL do not appear in the list. Fixing that means a
 * refresh-side channel upsert (the "统一 `CatalogSink`" follow-up), which is out of P2-6's boundary
 * because it changes the P2-4 pipeline.
 */
object CatalogPriority {

    /** The writer that owns the `channel` rows. */
    val channelRowsOwner: CatalogWriter = CatalogWriter.LOCAL_IMPORT

    /** True when [writer] may add or delete channels; false for a writer that only updates rows. */
    fun mayRewriteChannels(writer: CatalogWriter): Boolean = writer == channelRowsOwner

    /**
     * Which writer's rows a reader sees when both ran: the import's, because it replaced the table
     * wholesale and the refresh only edited rows inside it.
     */
    fun rowsAfterBoth(): CatalogWriter = channelRowsOwner
}
