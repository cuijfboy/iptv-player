package ilab.iptv.player.core.data.store

import ilab.iptv.player.core.data.mapper.MappedCatalog
import ilab.iptv.player.core.model.Stream

/**
 * The **one** write seam for "a catalog arrived — make it the current channel table".
 *
 * Why it exists: P2-1 and P2-6 both wrote a catalog, but through two different ends —
 * [RoomCatalogWriter] (SQLite) was only wired to the boot seeder, while the local import and the
 * fixture loader went through `ChannelCatalog.commit()` → [ChannelStore] (memory). Binding Room as
 * the production `ChannelRepository` while the import still wrote memory is the mismatch that forced
 * god's 2026-09-22 integration ruling (see `PersistenceModule`): the list would read Room, the import
 * would write memory, and nothing would change on screen. This interface is the fix — every source
 * (bundled fixture, local import, later a P2-4 refresh) writes through the *same* injected sink, so
 * "reads Room" and "writes Room" can be switched together.
 *
 * The contract is **replace**, not merge: the catalog passed in becomes the whole channel/stream
 * table. That is what [ChannelStore.replaceAll] already did for the memory path (docs/05-过程记录/19
 * §2 "导入即替换"), and keeping the two implementations on the same contract is the point of the
 * seam — a merge would silently leave the previous playlist's channels behind on an import.
 *
 * Implementations:
 * - [ChannelStore] — the in-memory snapshot (P1-2; retained for the off-device tests).
 * - [RoomCatalogWriter] — SQLite (P2-1; the production binding, see `PersistenceModule`).
 *
 * `suspend` because the production sink is Room and its transaction API is suspending: a blocking
 * write would have to run on the playback thread, which docs/02 §4.5 C6 forbids.
 */
interface CatalogSink {

    /**
     * Publishes [catalog] as the current catalog. [nowMs] is the wall-clock stamp the storage writes
     * onto its rows (`created_at` / `updated_at`); the in-memory sink ignores it.
     *
     * Returns how many rows were written (channels + streams), for the caller's log line.
     */
    suspend fun write(catalog: MappedCatalog, nowMs: Long): Int

    /**
     * The **refresh** half of the seam (卡 REFRESH-PERSIST-1): make every channel in [catalog] exist
     * in the store and return the map from the catalog's in-memory channel ids to the **stored** ids,
     * so a caller can write `stream` rows that satisfy the `stream.channel_id -> channel.id` foreign
     * key. Identity is the §5.1 key `(name_key, group_key)`, so an existing channel keeps its row —
     * and with it its id, the favourites / sort order that hang off that id, and every user-owned
     * column (the same split [ilab.iptv.player.core.database.dao.ChannelDao.upsertAll] documents).
     *
     * **Not [write], and deliberately so.** [write] is *replace*: it is the import / seed contract,
     * and it prunes the channels that are not in the catalog. A refresh must never replace the channel
     * table — the list the user is looking at came from the last import or the bundled snapshot, and a
     * source refresh only owns the **streams** of the channels it sees — so this method only ever adds
     * or updates the channels named in [catalog] and returns their ids. It writes no streams.
     *
     * Why the refresh needs it at all: the pipeline mints channel ids in memory (`ChannelMapper`), and
     * those ids are not database ids. Writing streams against them is what produced
     * `DB_FAIL FOREIGN KEY constraint failed (streams:466, written:0)` — the whole batch rolled back
     * because not one referenced channel existed. Resolving the ids here, through the one write seam,
     * is the fix: channels are upserted first, streams second, and both go through the same injected
     * sink as an import, so "reads Room / writes Room" stays one decision.
     */
    suspend fun upsertChannels(catalog: MappedCatalog, nowMs: Long): Map<Long, Long>

    /**
     * The **stream** half of the refresh seam (卡 STREAM-ID-1): map each stream's per-load id in
     * [streams] to the id of the stored `stream` row with the same §5.1 identity
     * `(channel_id, url_hash)`. Read-only — it writes nothing.
     *
     * Why the pipeline needs it: [ilab.iptv.player.core.data.mapper.ChannelMapper] mints stream ids
     * from a per-load counter, exactly as it mints channel ids, so a stream's in-memory id is not its
     * database id (the channel side of this mismatch is [upsertChannels]). Everything downstream
     * addresses a stream **by id** — the shallow stage stamps health through
     * `StreamRepository.recordOutcome(streamId)` and the scorer reads it back through
     * `StreamRepository.health(streamId)` — so a per-load id either misses its row (a silent no-op)
     * or, worse, hits a *different* stream and writes the probe verdict and its `play_history` row
     * onto a stranger. Resolving ids after the write, through this seam, is the fix.
     *
     * A stream the store does not hold gets no entry: with no row there is nothing to stamp, and
     * keeping the per-load id would be the mis-addressing this removes. A storage failure degrades
     * (docs/02 §11): the implementation logs `DB_FAIL` and returns an empty map.
     */
    suspend fun resolveStreamIds(streams: List<Stream>): Map<Long, Long>
}
