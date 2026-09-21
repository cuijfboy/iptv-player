package ilab.iptv.player.core.source.pipeline

/**
 * The single definition of the pipeline's concurrency and timeout limits (docs/02 §4.4 / §4.5 C3).
 *
 * Nothing else in the code base may hard-code one of these numbers: docs/02 §6.1's stage table is
 * *defined* by these defaults, so tuning the pipeline means editing this file and it alone. A
 * caller may still override an instance (tests do), but a production default lives here.
 */
data class PipelineLimits(
    val fetchConcurrency: Int = 4,
    val fetchTimeoutMs: Long = 15_000,
    val maxBytes: Long = 8L * 1024 * 1024,
    val shallowConcurrency: Int = 12,
    val shallowTimeoutMs: Long = 6_000,
    val deepConcurrency: Int = 6,
    val deepTimeoutMs: Long = 12_000,
    val capCandidates: Int = 3_000,
    val batchSize: Int = 500,
    /** Bytes the shallow GET fallback asks for when HEAD is refused (docs/02 §6.1 edge slice). */
    val shallowFirstBytes: Long = 4L * 1024,
)
