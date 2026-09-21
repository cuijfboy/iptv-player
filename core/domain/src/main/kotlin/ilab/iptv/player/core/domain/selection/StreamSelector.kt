package ilab.iptv.player.core.domain.selection

import ilab.iptv.player.core.model.SelectionInput
import ilab.iptv.player.core.model.Stream

/**
 * docs/02 §4.3 `StreamSelector`: ranks one channel's candidates, primary first.
 *
 * The key is **frozen** by the document — `score desc → priority asc → lastOkAtMs desc (null last)
 * → id asc` — and the last two keys are what make the order *stable*: two streams with the same
 * score never swap places between runs, which is what keeps "which one plays" reproducible.
 */
interface StreamSelector {
    fun rank(input: SelectionInput): List<Stream>
}
