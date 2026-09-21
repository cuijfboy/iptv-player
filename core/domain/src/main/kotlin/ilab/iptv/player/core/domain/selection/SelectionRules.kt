package ilab.iptv.player.core.domain.selection

import ilab.iptv.player.core.model.Stream

/**
 * The per-channel selection decision of docs/02 §6.1's Select stage:
 * "每频道：最高分为主、次高分（≥55）为备胎，最多 3 条".
 *
 * Three things the sentence leaves open, decided here and recorded in the P2-4b report:
 * 1. **the threshold applies to backups, not to the primary** — the document says the *highest* score
 *    is the primary and only names the ≥[MIN_BACKUP_SCORE] bar for backups, and docs/02 §8.3's
 *    degradation table wants a channel with a live-but-weak stream to stay playable ("仍可尝试
 *    播放"). So a low score alone never makes a channel unavailable; *failing verification* does;
 * 2. **"最多 3 条" means one primary plus at most two backups**;
 * 3. **nothing is deleted** — a stream that is not selected is returned in [StreamSelection.dropped]
 *    and the caller keeps the row (docs/01 F2: a channel with no usable stream stays visible).
 *
 * [verified] is the caller's playability verdict for this run (a passing deep probe, or a stream
 * whose health is still fresh) and is separate from the score on purpose: the 可用性 rule already
 * prices verification, but selection must not let an unverified stream in on the strength of the
 * other five dimensions.
 */
object SelectionRules {

    /** docs/02 §6.1: a backup must score at least this. */
    const val MIN_BACKUP_SCORE = 55

    /** docs/02 §6.1: "最多 3 条" per channel — one primary plus two backups. */
    const val MAX_STREAMS_PER_CHANNEL = 3

    /** Backups = [MAX_STREAMS_PER_CHANNEL] - 1. */
    const val MAX_BACKUPS = MAX_STREAMS_PER_CHANNEL - 1

    /**
     * @param ranked candidates already in [DefaultStreamSelector] order (primary first);
     * @param verified per-stream playability verdict for this run.
     */
    fun select(ranked: List<Stream>, verified: (Stream) -> Boolean): StreamSelection {
        val playable = ranked.filter(verified)
        val primary = playable.firstOrNull()
        val backups = playable.drop(1).filter { it.score >= MIN_BACKUP_SCORE }.take(MAX_BACKUPS)
        val selectedIds = HashSet<Long>(MAX_STREAMS_PER_CHANNEL)
        primary?.let { selectedIds += it.id }
        backups.forEach { selectedIds += it.id }
        val dropped = ranked.filterNot { it.id in selectedIds }
        return StreamSelection(primary = primary, backups = backups, dropped = dropped)
    }
}

/**
 * One channel's selection outcome. [primary] is null exactly when the channel has **no verified
 * stream at all** — the "keep it visible, mark it unavailable" case (docs/01 F2, docs/02 §8.3).
 */
data class StreamSelection(
    val primary: Stream?,
    val backups: List<Stream>,
    val dropped: List<Stream>,
) {
    val selected: List<Stream> = listOfNotNull(primary) + backups
    val isAvailable: Boolean get() = primary != null
}
