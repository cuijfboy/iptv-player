package ilab.iptv.player.core.source.provider

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.ValidationResult
import ilab.iptv.player.core.model.ValidationStage

/**
 * Extension point E1 (docs/02 §4.4, frozen shape): one public aggregate source.
 *
 * A new aggregate is added by implementing this interface and registering it through Hilt
 * `@IntoSet` — the pipeline is never edited (docs/02 §9 E1). [fetch] returns the parsed rows or an
 * [AppResult.Err]; it never throws (except `CancellationException`, rethrown — §4.0 F2).
 */
interface SourceProvider {
    val id: String
    val label: String
    val kind: SourceKind

    suspend fun fetch(clock: Clock): AppResult<List<RawEntry>>
}

/**
 * Extension point E2 (docs/02 §4.4, frozen shape): one link of the validation chain. Validators are
 * ordered by [order] and short-circuit on the first failure; [stage] is the cheap reachability pass
 * (`SHALLOW`) or the decode probe (`DEEP`).
 */
interface StreamValidator {
    val id: String
    val order: Int
    val stage: ValidationStage

    suspend fun validate(target: StreamTarget, ctx: ProbeContext): ValidationResult
}
