package ilab.iptv.player.core.domain.repository

import ilab.iptv.player.core.model.SourceConfig

/**
 * Source port (docs/02 §4.3, frozen shape). `:core:data` implements it over the `source` table
 * (docs/02 §5.1); the refresh pipeline reads it to turn the rows the user added into providers.
 *
 * The three methods below are exactly the ones docs/02 §4.3 froze. Two obvious extras were **not**
 * added on purpose:
 * - `enabled()`: `all().filter { it.enabled }` at the two call sites instead of widening a frozen
 *   signature (a §4.3 change needs arch + god, see docs/02 §4.0 F6);
 * - `get(id)`: the management screen already has the whole list in hand, so a lookup by id over
 *   `all()` is enough and keeps the port unchanged.
 *
 * The richer read model the subscription screen needs (last fetch time, last result, entry count —
 * columns docs/02 §5.1 already has on `source`) lives in
 * [ilab.iptv.player.core.domain.source.SourceManagementPort], not here, because [SourceConfig] is a
 * frozen §4.2 type and adding status fields to it would change that shape.
 */
interface SourceRepository {

    /** Every configured source, ordered by id so the list is stable across reads. */
    suspend fun all(): List<SourceConfig>

    /** Inserts or replaces one row, keyed by [SourceConfig.id]. */
    suspend fun upsert(cfg: SourceConfig)

    /** Removes one row. A missing id is not an error. */
    suspend fun remove(id: String)
}
