package ilab.iptv.player.core.domain.repository

import ilab.iptv.player.core.model.EpgChannelRef
import kotlinx.coroutines.flow.Flow

/**
 * The guide's channel list, as of the last EPG run (P3-4).
 *
 * The manual-binding picker needs something docs/02 §5.1 does not model: the *names* of the guide's
 * channels. `programme` only carries `epg_channel_id`, and an unmatched channel (the three HK/MO/TW
 * gaps, `docs/05-过程记录/39-EPG繁简折叠.md`) has no programme row at all, so the id alone cannot tell
 * the user whether the guide even has the channel. This port exposes the `<channel>` list the parser
 * emitted, which `:core:data` persists next to the guide.
 *
 * It is a **cache of derived data**, not a §5.1 table: the authoritative copy is the XMLTV file, and
 * the list is rebuilt on every run. Reading it is what lets the picker say "该 guide 无此频道" honestly
 * instead of offering a free-text id nobody can verify.
 */
interface EpgChannelCatalog {

    /** The guide channels from the last successful parse, ordered by display name. */
    fun observe(): Flow<List<EpgChannelRef>>

    /** Replaces the catalogue with [refs] (one EPG run's union of `<channel>` entries). */
    suspend fun replaceAll(refs: List<EpgChannelRef>)
}
