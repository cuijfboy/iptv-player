package ilab.iptv.player.core.epg

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock

/**
 * Extension point E4 (docs/02 §4.4, frozen shape): one XMLTV source.
 *
 * Adding an EPG source is adding a catalogue row ([BuiltInEpgSources]) plus one binding in the Hilt
 * module — the refresh pipeline itself never changes (docs/02 §9 E4). `fetch` answers with an
 * [AppResult] and never throws, except `CancellationException`, which is rethrown (docs/02 §4.0 F2).
 *
 * [clock] is passed in rather than read from a singleton so a unit test can pin "now" (docs/02 §4.1:
 * never call `System.currentTimeMillis` directly — the source's `last_fetch_at` is stamped from it).
 */
interface EpgProvider {
    val id: String
    val label: String

    suspend fun fetch(clock: Clock): AppResult<XmltvStream>
}
