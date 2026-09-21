package ilab.iptv.player.core.domain.selection

import ilab.iptv.player.core.model.SelectionInput
import ilab.iptv.player.core.model.Stream

/**
 * The frozen docs/02 §4.3 ordering: `score desc → priority asc → lastOkAtMs desc (null last) → id asc`.
 *
 * Pure and total: it never drops a candidate, it only orders them — dropping/limiting is
 * [SelectionRules]' job, so "why did this channel end up with no usable stream" stays one decision.
 */
class DefaultStreamSelector : StreamSelector {

    override fun rank(input: SelectionInput): List<Stream> = input.candidates.sortedWith(ORDER)

    companion object {
        /** Exposed so the persistence layer and tests share the exact same key. */
        val ORDER: Comparator<Stream> = compareByDescending<Stream> { it.score }
            .thenBy { it.priority }
            .thenByDescending { it.lastOkAtMs ?: Long.MIN_VALUE }
            .thenBy { it.id }
    }
}
