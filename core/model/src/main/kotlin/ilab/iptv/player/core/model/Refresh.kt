package ilab.iptv.player.core.model

/**
 * Refresh transfer model (docs/02 §4.2, frozen interface v1) — the types the source-refresh
 * pipeline (docs/02 §6.1, F2) is driven by and reports back through.
 *
 * P2-4a ships the front half (fetch → parse → normalize → dedupe → cap → shallow) plus the budget,
 * checkpoint and cancellation framework; deep probe / score / select are P2-4b.
 */

/** Why a refresh runs (docs/02 §4.2); the schedule itself is P2-5. */
enum class RefreshTrigger { SCHEDULED, MANUAL, FIRST_RUN, ON_DEMAND_SINGLE_CHANNEL }

/** The §6.1 pipeline stages, in order. `DONE` is the terminal success state. */
enum class RefreshPhase { FETCH, PARSE, NORMALIZE, DEDUPE, SHALLOW, DEEP, SCORE, SELECT, PERSIST, DONE }

/**
 * One refresh request. [budgetMs] defaults to the frozen 45 minutes (docs/02 §6.1) and
 * [respectPlayback] is the R7 playback-avoidance switch (halve Fetch/Shallow/Deep concurrency while
 * a session plays).
 */
data class RefreshOptions(
    val trigger: RefreshTrigger,
    val budgetMs: Long = 45 * 60_000L,
    val respectPlayback: Boolean = true,
    val channelIdOnly: Long? = null,
)

/**
 * Progress of one refresh run. `total` is the size of the phase's input, `done` how much of it is
 * finished; `interrupted` is set on the terminal emission when the run stopped early (docs/02 §6.1).
 */
data class RefreshProgress(
    val phase: RefreshPhase,
    val done: Int,
    val total: Int,
    val okCount: Int,
    val failCount: Int,
    val elapsedMs: Long,
    val interrupted: RefreshInterruption? = null,
)

/** Why a run stopped before [RefreshPhase.DONE] (docs/02 §4.2). */
data class RefreshInterruption(
    val reason: InterruptionReason,
    val phase: RefreshPhase,
)

enum class InterruptionReason { BUDGET_EXCEEDED, CANCELLED, PLAYBACK_PRIORITY, ERROR }
