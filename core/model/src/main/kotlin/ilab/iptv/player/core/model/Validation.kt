package ilab.iptv.player.core.model

/**
 * Validation transfer model (docs/02 §4.2, frozen interface v1).
 *
 * The two-stage chain is docs/02 §6.1: a cheap **SHALLOW** reachability pass (can we connect at
 * all — HEAD / first slice) and an expensive **DEEP** pass (Media3 actually decodes it). P2-4a
 * ships the shallow stage and the framework; the deep probe and the scorer are P2-4b.
 */

/** Which half of the chain a [StreamValidator] belongs to (docs/02 §6.1). */
enum class ValidationStage { SHALLOW, DEEP }

/** What a validator is handed for one stream: no `Stream` id, so a probe can run before persist. */
data class StreamTarget(
    val url: String,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * Per-call probe budget and context. [timeoutMs] is the stage timeout from `PipelineLimits`
 * (docs/02 §4.4) and [nowMs] is the injected clock reading, so a validator stays testable.
 */
data class ProbeContext(
    val stage: ValidationStage,
    val timeoutMs: Long,
    val engineCaps: Set<EngineCapability>,
    val nowMs: Long,
)

/**
 * One validator's verdict. [evidence] carries the raw observations (status, bytes, costMs) that
 * `VAL_SHALLOW_OK` / `VAL_SHALLOW_FAIL` log and the report cites (docs/03 §3.3).
 */
data class ValidationResult(
    val passed: Boolean,
    val detail: String,
    val evidence: Map<String, Any?> = emptyMap(),
)
